package io.zdp.node.service.network;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.zdp.crypto.Signing;
import io.zdp.node.network.validation.ValidationNetworkClient;

@Component
public class NetworkTopologyService {

	private Logger log = LoggerFactory.getLogger( this.getClass() );

	private List < NetworkNode > nodes;

	@Autowired
	private ValidationNetworkClient validationNetworkClient;

	private String vnlFileContent;

	private Date lastRefreshDate;

	@Scheduled ( fixedDelay = 60 * DateUtils.MILLIS_PER_SECOND )
	public synchronized void init ( ) {

		log.debug( "Refreshing network configuration" );

		try {
			// Download public VNL (List of Validation Nodes) file
			final URL url = new File( "vnl.json" ).toURI().toURL();

			final ObjectMapper jsonMapper = new ObjectMapper();
			jsonMapper.disable( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES );

			try ( InputStream in = url.openStream() ) {
				vnlFileContent = IOUtils.toString( in, StandardCharsets.UTF_8 );
			}

			final List < NetworkNode > topology = jsonMapper.readValue( vnlFileContent, new TypeReference < List < NetworkNode > >() {
			} );

			if ( nodes == null || false == CollectionUtils.isEqualCollection( topology, nodes ) ) {

				log.debug( "VNL: " + vnlFileContent );
				
				nodes = topology;

				for ( NetworkNode node : nodes ) {
					node.setNodeType( NetworkNodeType.VALIDATING );
				}

				log.debug( "Loaded/reloaded list of validation nodes: " + nodes );

				lastRefreshDate = new Date();

				validationNetworkClient.init();

			} else {
				log.debug( "No change in network topology" );
			}

		} catch ( Exception e ) {
			log.error( "Error: ", e );
		}

	}

	public List < NetworkNode > getNodes ( ) {

		if ( nodes == null ) {
			init();
		}

		return nodes;

	}

	public NetworkNode getNodeByUuid ( String uuid ) {
		for ( NetworkNode n : getNodes() ) {
			if ( uuid.equals( n.getUuid() ) ) {
				return n;
			}
		}
		return null;
	}

	public boolean isValidServerRequest ( String serverUuid, byte [ ] data, byte [ ] signature ) {

		NetworkNode node = this.getNodeByUuid( serverUuid );

		if ( node != null ) {

			try {
				return Signing.isValidSignature( node.getECPublicKey(), data, signature );
			} catch ( Exception e ) {
				log.error( "Error: ", e );
			}
		}

		return false;

	}

	public String getVnlFileContent ( ) {
		return vnlFileContent;
	}

	public Date getLastRefreshDate ( ) {
		return lastRefreshDate;
	}

}
