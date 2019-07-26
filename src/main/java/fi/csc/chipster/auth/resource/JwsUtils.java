package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.PublicKey;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMException;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

public class JwsUtils {
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
	
	public static KeyPair pemToKeyPair(String pem) throws PEMException{
				
		PEMKeyPair pemKeyPair = (PEMKeyPair) parsePem(pem);			
		JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
		return converter.getKeyPair(pemKeyPair);
	}
	
	public static PublicKey pemToPublicKey(String pem) throws PEMException{
		
		SubjectPublicKeyInfo publicKeyInfo = (SubjectPublicKeyInfo) parsePem(pem);
		JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
		return converter.getPublicKey(publicKeyInfo);
	}

}
