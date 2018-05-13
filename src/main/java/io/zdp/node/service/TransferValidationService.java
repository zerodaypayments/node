package io.zdp.node.service;

import java.math.BigDecimal;
import java.security.PublicKey;
import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.zdp.api.model.v1.TransferRequest;
import io.zdp.api.model.v1.TransferResponse;
import io.zdp.crypto.Base58;
import io.zdp.crypto.Hashing;
import io.zdp.crypto.Keys;
import io.zdp.crypto.Signing;
import io.zdp.crypto.account.ZDPAccountUuid;
import io.zdp.node.dao.jpa.AccountDao;
import io.zdp.node.dao.jpa.TransferDao;
import io.zdp.node.domain.Account;
import io.zdp.node.error.TransferException;

@Service
public class TransferValidationService {

	private Logger log = LoggerFactory.getLogger(this.getClass());

	public static final BigDecimal TX_FEE = BigDecimal.valueOf(0.0001);

	@Autowired
	private AccountDao accountDao;

	@Autowired
	private TransferDao transferDao;

	@Transactional(readOnly = true)
	public boolean isValid(TransferRequest request) throws TransferException {

		log.debug("Request: " + request);

		try {

			// Validate addresses
			if (false == ZDPAccountUuid.isValidUuid(request.getFrom())) {
				log.error("Not valid FROM address: " + request.getFrom());
				throw new TransferException(TransferResponse.ERROR_INVALID_FROM_ACCOUNT);
			}

			if (false == ZDPAccountUuid.isValidUuid(request.getTo())) {
				log.error("Not valid TO address: " + request.getTo());
				throw new TransferException(TransferResponse.ERROR_INVALID_TO_ACCOUNT);
			}

			// Should contain only digits and one optional dot
			if (false == StringUtils.containsOnly(request.getAmount(), "0123456789.")) {
				log.error("Amount is not valid: " + request.getAmount());
				throw new TransferException(TransferResponse.ERROR_INVALID_AMOUNT);
			}

			// Check amount
			if (false == NumberUtils.isCreatable(request.getAmount())) {
				log.error("Amount is not valid: " + request.getAmount());
				throw new TransferException(TransferResponse.ERROR_INVALID_AMOUNT);
			}

			// Validate amount (must be > 0)
			if (request.getAmountAsBigDecimal().compareTo(BigDecimal.ZERO) <= 0) {
				log.error("Amount is not valid: " + request.getAmountAsBigDecimal());
				throw new TransferException(TransferResponse.ERROR_INVALID_AMOUNT);
			}

			// Extract FROM address

			final ZDPAccountUuid fromAccountUuid = new ZDPAccountUuid(request.getFrom());

			if (false == Arrays.equals(fromAccountUuid.getPublicKeyHash(), Hashing.hashPublicKey(Base58.decode(request.getPublicKey())))) {
				log.error("Don't think the tx signer is authorized to act on this FROM account");
				throw new TransferException(TransferResponse.ERROR_TX_SIGNATURE_UNAUTHORIZED);
			}

			final Account fromAccount = this.accountDao.findByUuid(fromAccountUuid.getPublicKeyHash());

			if (fromAccount == null) {
				log.debug("Not found FROM account: " + fromAccountUuid);
				throw new TransferException(TransferResponse.ERROR_INVALID_FROM_ACCOUNT);
			}

			// Validate signature
			final String pubKeyCurve = fromAccountUuid.getCurve();

			final PublicKey pubKey = Keys.toPublicKey(Base58.decode(request.getPublicKey()), pubKeyCurve);

			byte[] signature = request.getTransferUuid();

			// Check if such a tx exists, if so, return
			if (transferDao.findByUuid(signature) != null) {
				throw new TransferException(TransferResponse.ERROR_TX_REPLAY);
			}

			final boolean validSignature = Signing.isValidSignature(pubKey, signature, request.getSignature());

			log.debug("validSignature: " + validSignature);

			if (validSignature) {

				// From should have enough to transfer + fee
				final BigDecimal totalAmount = request.getAmountAsBigDecimal().add(TX_FEE);

				log.debug("totalAmount: " + totalAmount);

				if (fromAccount.getBalance().compareTo(totalAmount) < 0) {
					throw new TransferException(TransferResponse.ERROR_INSUFFICIENT_FUNDS);
				}

			} else {

				log.error("Signature not verified");

				throw new TransferException(TransferResponse.ERROR_TX_SIGNATURE_UNAUTHORIZED);

			}

		} catch (TransferException ex) {

			log.error("Error: ", ex);

			throw ex;

		} catch (Exception e) {

			log.error("Error: ", e);

			throw new TransferException(TransferResponse.ERROR_SYSTEM);

		}

		return true;

	}

}