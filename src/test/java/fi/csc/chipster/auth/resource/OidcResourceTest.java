package fi.csc.chipster.auth.resource;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.ForbiddenException;

import org.junit.Assert;
import org.junit.Test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.rest.RestUtils;

public class OidcResourceTest {
	
	@Test
    public void hasRequiredClaim() throws URISyntaxException, IOException {

		final String OIDC_NAME = "testOidc";
		final String CLAIM_KEY = "claimKey";
		final String CLAIM_VALUE = "claimValue";
		
		
		Map<String, Object> claims = new HashMap<String, Object>() {{
			put(CLAIM_KEY, CLAIM_VALUE);
		}};
		
		OidcResource resource = new OidcResource();
		
		// the default, no claim required
		assertEquals(true, resource.hasRequiredClaim(OIDC_NAME, "", claims));
		
		// the CLAIM_KEY must be found, but the value can be anything
		assertEquals(true, resource.hasRequiredClaim(OIDC_NAME, CLAIM_KEY, claims));
		
		// the CLAIM_KEY is not found
		assertEquals(false, resource.hasRequiredClaim(OIDC_NAME, "nonExistingClaimKey", claims));
		
		// the CLAIM_KEY and CLAIM_VALUE must match
		assertEquals(true, resource.hasRequiredClaim(OIDC_NAME, CLAIM_KEY + "=" + CLAIM_VALUE, claims));
		
		// incorret CLAIM_VALUE
		assertEquals(false, resource.hasRequiredClaim(OIDC_NAME, CLAIM_KEY + "=" + "expectedAnotherValue", claims));
    }
	
	@Test
	public void getOidcConfig() throws URISyntaxException, IOException {
		
		final String ISSUER12 = "issuer12";
		final String CLIENT_ID12 = "clientId12";
		final String USER_ID_CLAIM_KEY = "userIdClaimKey";
		final String REQUIRED_CLAIM_KEY1 = "requiredClaimKey1";
		final String REQUIRED_CLAIM_KEY2 = "requiredClaimKey2";
		
		// oidc1 and oidc2 have same issuer and clientId, but different required claim
		OidcConfig oidc1 = new OidcConfig();		
		oidc1.setIssuer(ISSUER12);
		oidc1.setClientId(CLIENT_ID12);
		oidc1.setOidcName("oidcName1");
		oidc1.setClaimUserId(USER_ID_CLAIM_KEY);
		oidc1.setRequireClaim(REQUIRED_CLAIM_KEY1);
		oidc1.setPriority(1);
		
		OidcConfig oidc2 = new OidcConfig();		
		oidc2.setIssuer(ISSUER12);
		oidc2.setClientId(CLIENT_ID12);
		oidc2.setOidcName("oidcName2");
		oidc2.setClaimUserId(USER_ID_CLAIM_KEY);
		oidc2.setRequireClaim(REQUIRED_CLAIM_KEY2);
		oidc2.setPriority(2);
		
		final String ISSUER3 = "issuer3";
		final String CLIENT_ID3 = "clientId3";
		
		// oidc3 is completely different
		OidcConfig oidc3 = new OidcConfig();		
		oidc3.setIssuer(ISSUER3);
		oidc3.setClientId("clientId3");
		oidc3.setOidcName("oidcName3");
		oidc3.setClaimUserId("sub");
		oidc3.setRequireClaim("");		
		oidc3.setPriority(3);
		
		OidcResource resource = new OidcResource();
				
		resource.setOidcConfigs(new ArrayList<OidcConfig>() {{
			add(oidc1);
			add(oidc2);
			add(oidc3);
		}});
		
		HashMap<String, Object> claims = new HashMap<String, Object>() {{
			put("sub", "subValue");
		}};
		
		try {
			resource.getOidcConfig("non-existing issuer", null, claims);
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		try {
			resource.getOidcConfig(ISSUER12, "incorrectClientId", claims);
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		try {
			resource.getOidcConfig(ISSUER12, "incorrectClientId", claims);
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// userId claim missing
		try {
			resource.getOidcConfig(ISSUER12, CLIENT_ID12, claims);
			Assert.fail();
		} catch (ForbiddenException e) { }
				
		// no required claims
		assertEquals(oidc3.getOidcName(), resource.getOidcConfig(ISSUER3, CLIENT_ID3, claims).getOidcName());
		
		// required claim missing
		try {
			resource.getOidcConfig(ISSUER12, CLIENT_ID12, claims);
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		HashMap<String, Object> claims2 = new HashMap<>() {{
			put(USER_ID_CLAIM_KEY, "userIdValue");
			put(REQUIRED_CLAIM_KEY1, "requiredClaimValue1");
		}};
		
		// should select oidc1 based on the required claim 
		assertEquals(oidc1.getOidcName(), resource.getOidcConfig(ISSUER12, CLIENT_ID12, claims2).getOidcName());
	}
	
	@Test
	public void validateIdToken() throws URISyntaxException, IOException, JOSEException, ParseException {
		
		final String ISSUER = "issuer";
		final String CLIENT_ID = "clientId";		
		
		RSAKey privateKey = new RSAKeyGenerator(2048).generate();
		RSAKey publicKey = privateKey.toPublicJWK();
		
		RSAKey privateKey2 = new RSAKeyGenerator(2048).generate();
		
		HashMap<String, Object> claims = new HashMap<String, Object>() {{
			put("sub", "userId");
			put("iss", ISSUER);
			put("aud", CLIENT_ID);
			put("iat", Instant.now().getEpochSecond());
			put("exp", Instant.now().getEpochSecond() + 10);
		}};
		
		String jws = getIdToken(privateKey, claims, JWSAlgorithm.RS256);
		
		OidcResource resource = new OidcResource();
		
		IDTokenValidator validator = resource.getValidator(ISSUER, CLIENT_ID, null, new JWKSet(publicKey));

		// valid
		resource.validateIdToken(JWTParser.parse(jws), validator);				
		
		// wrong iss
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claims) {{				
				put("iss", "wrong-iss");
			}};
			String jws2 = getIdToken(privateKey, claims2, JWSAlgorithm.RS256);
			resource.validateIdToken(JWTParser.parse(jws2), validator);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// wrong aud
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claims) {{				
				put("aud", "wrong-aud");
			}};
			String jws2 = getIdToken(privateKey, claims2, JWSAlgorithm.RS256);
			resource.validateIdToken(JWTParser.parse(jws2), validator);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// expired
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claims) {{				
				put("exp", Instant.now().getEpochSecond() - 1000);
			}};
			String jws2 = getIdToken(privateKey, claims2, JWSAlgorithm.RS256);
			resource.validateIdToken(JWTParser.parse(jws2), validator);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// from future
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claims) {{				
				put("iat", Instant.now().getEpochSecond() + 1000);
			}};
			String jws2 = getIdToken(privateKey, claims2, JWSAlgorithm.RS256);
			resource.validateIdToken(JWTParser.parse(jws2), validator);				
			Assert.fail();
		} catch (ForbiddenException e) { }

		// wrong key
		try {
			String jws2 = getIdToken(privateKey2, claims, JWSAlgorithm.RS256);
			resource.validateIdToken(JWTParser.parse(jws2), validator);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		/* 
		 * The Nimbus library makes it really difficult to make these kind of errors, I wasn't even able to test
		 * if the following two test really work. Let's try anyway:
		 */
		
		// signed with public key and symmetric algorithm. 
		try {
			String jws2 = getIdTokenSymmetric(publicKey.toString(), claims, JWSAlgorithm.HS256);
			resource.validateIdToken(JWTParser.parse(jws2), validator);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// signed with public key and none algorithm
		try {
			String jws2 = getIdTokenNone(publicKey.toString(), claims);
			resource.validateIdToken(JWTParser.parse(jws2), validator);				
			Assert.fail();
		} catch (ForbiddenException e) { }
	}

	private String getIdToken(RSAKey rsaJWK, HashMap<String, Object> claims, JWSAlgorithm algorithm) throws JOSEException {
		
		// https://connect2id.com/products/nimbus-jose-jwt/examples/jws-with-rsa-signature
		
		// Create RSA-signer with the private key
		JWSSigner signer = new RSASSASigner(rsaJWK);
		
		JWSHeader header = new JWSHeader.Builder(algorithm).keyID(rsaJWK.getKeyID()).build();
		Payload payload = new Payload(RestUtils.asJson(claims));

		// Prepare JWS object with simple string as payload
		JWSObject jwsObject = new JWSObject(header, payload);

		// Compute the RSA signature
		jwsObject.sign(signer);		

		// To serialize to compact form, produces something like
		// eyJhbGciOiJSUzI1NiJ9.SW4gUlNBIHdlIHRydXN0IQ.IRMQENi4nJyp4er2L
		// mZq3ivwoAjqa1uUkSBKFIX7ATndFF5ivnt-m8uApHO4kfIFOrW7w2Ezmlg3Qd
		// maXlS9DhN0nUk_hGI3amEjkKd0BWYCB8vfUbUv0XGjQip78AI4z1PrFRNidm7
		// -jPDm5Iq0SZnjKjCNS5Q15fokXZc8u0A
		String jws = jwsObject.serialize();
		
		return jws;
	}
	
	/**
	 * @param key
	 * @param claims
	 * @param algorithm
	 * @return
	 * @throws KeyLengthException
	 * @throws JOSEException
	 */
	private String getIdTokenSymmetric(String key, HashMap<String, Object> claims, JWSAlgorithm algorithm) throws KeyLengthException, JOSEException {

		// Create the header
		JWSHeader header = new JWSHeader(algorithm);

		// Set the plain text
		Payload payload = new Payload(RestUtils.asJson(claims));

		// Create the JWE object and encrypt it
		JWSObject jwsObject = new JWSObject(header, payload);
		MACSigner signer = new MACSigner(key);
		jwsObject.sign(signer);
		
		return jwsObject.serialize();
	}
	
	private String getIdTokenNone(String key, HashMap<String, Object> claims) throws KeyLengthException, JOSEException, ParseException {

		// Create the header
		PlainHeader header = new PlainHeader();

		JWTClaimsSet payload = JWTClaimsSet.parse(RestUtils.asJson(claims));

		// Create the JWE object and encrypt it
		PlainJWT jwsObject = new PlainJWT(header, payload);
		
		return jwsObject.serialize();
	}
}
