package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMException;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import fi.csc.chipster.rest.Config;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureAlgorithm;

public class JwsUtils {

	private static Logger logger = LogManager.getLogger();

	private static final String KEY_JWS_PRIVATE_KEY = "jws-private-key";
	private static final String KEY_JWS_ALGORITHM = "jws-algorithm";

	public static SignatureAlgorithm getSignatureAlgorithm(Config config, String role) {
		return (SignatureAlgorithm)Jwts.SIG.get().get(config.getString(KEY_JWS_ALGORITHM, role));
	}

	public static KeyPair getOrGenerateKeyPair(Config config, String role, SignatureAlgorithm signatureAlgorithm)
			throws PEMException {
		String privateKeyString = config.getString(KEY_JWS_PRIVATE_KEY, role);

		KeyPair keyPair = null;
		if (!privateKeyString.trim().isEmpty()) {

			logger.info("use configured jws private key");
			// the library generates the public key automatically from the private key
			keyPair = JwsUtils.pemToKeyPair(privateKeyString);

		} else {

			logger.warn("jws private key not configured, generate new");
			/*
			 * Generating a new key is secure, but may cause authentication errors, when the
			 * auth is restarted.
			 * 
			 * Command for generating a fixed key
			 * (https://connect2id.com/products/nimbus-jose-jwt/openssl-key-generation):
			 * openssl ecparam -genkey -name secp521r1 -noout -out ec512-key-pair.pem
			 * 
			 */
			keyPair = signatureAlgorithm.keyPair().build();
		}

		logger.info(role + " jws private key:" + keyPair.getPrivate().getAlgorithm() + " "
				+ keyPair.getPrivate().getFormat());
		logger.info(
				role + " jws public key:" + keyPair.getPublic().getAlgorithm() + " " + keyPair.getPublic().getFormat());

		return keyPair;
	}

	/**
	 * Convert byte array key to PEM format
	 * 
	 * @param key
	 * @return
	 */
	public static String publicKeyToPem(PublicKey publicKey) {

		StringWriter writer = new StringWriter();

		try (PemWriter pemWriter = new PemWriter(writer)) {
			pemWriter.writeObject(new PemObject("PUBLIC KEY", publicKey.getEncoded()));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return writer.toString();
	}

	public static Object parsePem(String pem) {
		PEMParser pemParser = new PEMParser(new StringReader(pem));
		try {
			return pemParser.readObject();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			try {
				pemParser.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static KeyPair pemToKeyPair(String pem) throws PEMException {

		PEMKeyPair pemKeyPair = (PEMKeyPair) parsePem(pem);
		JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
		return converter.getKeyPair(pemKeyPair);
	}

	public static PublicKey pemToPublicKey(String pem) throws PEMException {

		SubjectPublicKeyInfo publicKeyInfo = (SubjectPublicKeyInfo) parsePem(pem);
		JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
		return converter.getPublicKey(publicKeyInfo);
	}
}
