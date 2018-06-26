package io.zdp.node.service.validation.mq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.jms.ConnectionFactory;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Service;

import io.zdp.model.network.NetworkNode;
import io.zdp.model.network.NetworkTopologyListener;
import io.zdp.model.network.NetworkTopologyService;
import io.zdp.node.service.LocalNodeService;
import io.zdp.node.service.validation.balance.get.BalanceRequestTopicListener;
import io.zdp.node.service.validation.balance.get.BalanceResponse;
import io.zdp.node.service.validation.balance.update.UpdateBalanceRequestTopicListener;
import io.zdp.node.service.validation.failed.FailedTransferRequestTopicListener;
import io.zdp.node.service.validation.getAccounts.GetNodeAccountsRequestTopicListener;
import io.zdp.node.service.validation.getAccounts.GetNodeAccountsResponse;
import io.zdp.node.service.validation.service.ValidationNodeSigner;
import io.zdp.node.service.validation.settle.TransferSettlementRequestTopicListener;

@Service
public class ValidationNetworkMQ implements NetworkTopologyListener {

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	@Autowired
	private NetworkTopologyService networkTopologyService;

	@Autowired
	private LocalNodeService nodeConfigurationService;

	@Autowired
	private GetNodeAccountsRequestTopicListener getNodeAccountsRequestTopicListener;

	@Autowired
	private TransferSettlementRequestTopicListener transferSettlementRequestTopicListener;

	@Autowired
	private FailedTransferRequestTopicListener failedTransferRequestTopicListener;

	@Autowired
	private BalanceRequestTopicListener getBalanceRequestTopicListener;

	@Autowired
	private UpdateBalanceRequestTopicListener updateBalanceRequestTopicListener;

	@Autowired
	private ValidationNodeSigner validationNodeSigner;

	@Autowired
	@Qualifier("default-task-executor")
	private TaskExecutor taskExecutor;

	private Map<NetworkNode, List<DefaultMessageListenerContainer>> listeners = new HashMap<>();

	private Map<String, JmsTemplate> queueTransferNewResponseJmsTemplates = new HashMap<>();

	private Map<String, JmsTemplate> queueAccountBalanceResponseJmsTemplates = new HashMap<>();

	@PostConstruct
	public void init() {
		onChange();
		this.networkTopologyService.addChangeListener(this);
	}

	@Override
	public void onChange() {

		// TODO don't change not modified nodes, remove obsolete and re/create
		// new ones only

		log.debug("Network topology changed, rebuild ");

		final List<NetworkNode> nodes = this.networkTopologyService.getAllBut(this.nodeConfigurationService.getNode());

		log.debug("Will listen to " + nodes.size() + " nodes: ");
		for (NetworkNode node : nodes) {
			log.debug(node.getAmqHostname() + ":" + node.getAmqPort());
		}

		for (NetworkNode remoteNode : nodes) {

			listeners.put(remoteNode, new ArrayList<DefaultMessageListenerContainer>());

			final String brokerURL = "tcp://" + remoteNode.getAmqHostname() + ":" + remoteNode.getAmqPort();
			log.debug("remote brokerURL: " + brokerURL);

			// Cache AMQ objects
			ActiveMQConnectionFactory amqcf = new ActiveMQConnectionFactory();
			amqcf.setBrokerURL(brokerURL);
			amqcf.setTrustedPackages(Arrays.asList("io.zdp.node"));

			org.apache.activemq.pool.PooledConnectionFactory pcf = new org.apache.activemq.pool.PooledConnectionFactory();
			pcf.setConnectionFactory(amqcf);
			pcf.setCreateConnectionOnStartup(true);
			pcf.setMaxConnections(1024);
			pcf.setUseAnonymousProducers(true);

			// Remote GetAccounts request
			{
				DefaultMessageListenerContainer l = createListener(pcf, MQNames.TOPIC_TRANSFER_NEW_REQ, getNodeAccountsRequestTopicListener);
				listeners.get(remoteNode).add(l);
			}

			{
				DefaultMessageListenerContainer l = createListener(pcf, MQNames.TOPIC_SETTLED_TRANSFER_REQ, transferSettlementRequestTopicListener);
				listeners.get(remoteNode).add(l);
			}

			{
				DefaultMessageListenerContainer l = createListener(pcf, MQNames.TOPIC_FAILED_TRANSFER_REQ, failedTransferRequestTopicListener);
				listeners.get(remoteNode).add(l);
			}

			{
				DefaultMessageListenerContainer l = createListener(pcf, MQNames.TOPIC_GET_BALANCE_REQ, getBalanceRequestTopicListener);
				listeners.get(remoteNode).add(l);
			}

			{
				DefaultMessageListenerContainer l = createListener(pcf, MQNames.TOPIC_VALIDATION_NETWORK_UPDATE_BALANCE_REQ, updateBalanceRequestTopicListener);
				listeners.get(remoteNode).add(l);
			}

			{
				JmsTemplate t = new JmsTemplate(pcf);
				t.setDefaultDestinationName(MQNames.QUEUE_TRANSFER_NEW_RESP);
				t.afterPropertiesSet();
				queueTransferNewResponseJmsTemplates.put(remoteNode.getUuid(), t);
			}

			{
				JmsTemplate t = new JmsTemplate(pcf);
				t.setDefaultDestinationName(MQNames.QUEUE_GET_BALANCE_RESP);
				t.afterPropertiesSet();
				queueAccountBalanceResponseJmsTemplates.put(remoteNode.getUuid(), t);
			}

		}

	}

	private DefaultMessageListenerContainer createListener(ConnectionFactory pcf, String name, Object listener) {
		DefaultMessageListenerContainer l = new DefaultMessageListenerContainer();
		l.setConnectionFactory(pcf);
		l.setDestinationName(name);
		l.setPubSubDomain(true);
		l.setTaskExecutor(taskExecutor);
		l.setMessageListener(listener);
		l.afterPropertiesSet();
		l.start();
		return l;
	}

	/**
	 * Send confirmed transfer back to the originating server
	 */
	public void send(final String serverUuid, final GetNodeAccountsResponse c) {

		log.debug("Send [" + c + "] to [" + serverUuid + "]");

		try {
			validationNodeSigner.sign(c);
		} catch (Exception e) {
			log.error("Error: ", e);
		}

		this.queueTransferNewResponseJmsTemplates.get(serverUuid).convertAndSend(c);

	}

	public void send(String serverUuid, BalanceResponse resp) {

		log.debug("Send [" + resp + "] to [" + serverUuid + "]");

		try {
			validationNodeSigner.sign(resp);
		} catch (Exception e) {
			log.error("Error: ", e);
		}

		this.queueAccountBalanceResponseJmsTemplates.get(serverUuid).convertAndSend(resp);

	}

}
