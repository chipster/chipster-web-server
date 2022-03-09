package fi.csc.chipster.auth;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;

import jakarta.ws.rs.ForbiddenException;

import org.hibernate.Session;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import fi.csc.chipster.auth.AuthenticationService;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.auth.resource.OidcConfig;
import fi.csc.chipster.auth.resource.OidcProviders;
import fi.csc.chipster.auth.resource.OidcResource;
import fi.csc.chipster.auth.resource.UserTable;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;

public class OidcResourceTest {
	
	private static final String PREFIX1 = "prefix1";
	private static final String PREFIX2 = "prefix2";
	private static final String PREFIX3 = "prefix3";
	private static final String PREFIX4 = "prefix4";
	private static final String PREFIX5 = "prefix5";
	private static final String ISSUER12 = "issuer12";
	private static final String CLIENT_ID12 = "clientId12";
	private static final String USER_ID_CLAIM_KEY = "userIdClaimKey";
	private static final String REQUIRED_CLAIM_KEY1 = "requiredClaimKey1";
	private static final String REQUIRED_CLAIM_KEY2 = "requiredClaimKey2";
	private static final String REQUIRED_CLAIM_KEY = "requiredClaimKey";
	private static final String ISSUER3 = "issuer3";
	private static final String ISSUER4 = "issuer4";
	private static final String ISSUER5 = "issuer5";
	private static final String CLIENT_ID3 = "clientId3";
	private static final String CLIENT_ID4 = "clientId4";
	private static final String CLIENT_ID5 = "clientId5";
	private static final String REQUIRED_CLAIM_VALUE2 = "requiredClaimValue2";
	private static final String REQUIRED_CLAIM_VALUE_JSON = "[\"a\", \"b\"]";
	
	private static TestServerLauncher launcher;
	private static HibernateUtil hibernate;
	private static OidcProvidersMock oidcProviderMock;
	private static OidcResource oidcResource;
	
    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
    			
    	// let's use the real UserTable and AuthTable because other tests require the backend anyway
    	// alternatively we could mock those too
    	hibernate = new HibernateUtil(config, Role.AUTH, AuthenticationService.hibernateClasses);    	    	
    	AuthTokens tokenTable = new AuthTokens(config);
    	UserTable userTable = new UserTable(hibernate);
    	
    	oidcProviderMock = new OidcProvidersMock(getTestOidcConfigs());		
    	oidcResource = new OidcResource(oidcProviderMock);
    	oidcResource.init(tokenTable, userTable, config);
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
	
	
	@Test
	public void createTokenFromOidc() throws Exception {		
    	
		// store and throw any exceptions to fail the test
    	Exception exception = hibernate.runInTransaction(new HibernateRunnable<Exception>() {
    		
			@Override
			public Exception run(Session hibernateSession) {
				try {
					createTokenFromOidc2(oidcProviderMock, oidcResource);
				} catch (Exception e) {
					return e;
				}
				return null;
			}
    	
    	});
    	if (exception != null) {
    		throw exception;
    	}
	}
    	
    public void createTokenFromOidc2(OidcProvidersMock oidcProviderMock, OidcResource resource) throws JOSEException, GeneralSecurityException, IOException, ParseException, URISyntaxException, InterruptedException {
				
		RSAKey privateKey2 = new RSAKeyGenerator(2048).generate();
		
		HashMap<String, Object> claimsIssuer3 = new HashMap<String, Object>() {{
			put("sub", "userId");
			put("iss", ISSUER3);
			put("aud", CLIENT_ID3);
			put("iat", Instant.now().getEpochSecond());
			put("exp", Instant.now().getEpochSecond() + 10);
		}};
		
		String jws = oidcProviderMock.getIdToken(claimsIssuer3);

		// valid issuer3
		String chipsterToken = resource.createTokenFromOidc(jws, null);
		assertEquals(PREFIX3, new UserId(parseChipsterToken(chipsterToken).get("sub").toString()).getAuth());
		
		
		// valid for issuer 1. User
		HashMap<String, Object> claimsIssuer1 = new HashMap<>(claimsIssuer3) {{
			put("aud", CLIENT_ID12);
			put("iss", ISSUER12);
			put(USER_ID_CLAIM_KEY, "userIdValue");
			put(REQUIRED_CLAIM_KEY1, "anyClaimValue");
		}};
		String jws3 = oidcProviderMock.getIdToken(claimsIssuer1);
		chipsterToken = resource.createTokenFromOidc(jws3, null);
		assertEquals(PREFIX1, new UserId(parseChipsterToken(chipsterToken).get("sub").toString()).getAuth());
		
		
		// valid for issuer 2
		HashMap<String, Object> claimsIssuer2 = new HashMap<>(claimsIssuer3) {{
			put("aud", CLIENT_ID12);
			put("iss", ISSUER12);
			put(USER_ID_CLAIM_KEY, "userIdValue");
			put(REQUIRED_CLAIM_KEY2, REQUIRED_CLAIM_VALUE2);
		}};
		String jws4 = oidcProviderMock.getIdToken(claimsIssuer2);
		chipsterToken = resource.createTokenFromOidc(jws4, null);
		assertEquals(PREFIX2, new UserId(parseChipsterToken(chipsterToken).get("sub").toString()).getAuth());
				
		// issuer 4 requires any of "a" or "b"
		HashMap<String, Object> claimsIssuer4 = new HashMap<>(claimsIssuer3) {{
			put("aud", CLIENT_ID4);
			put("iss", ISSUER4);
			put(REQUIRED_CLAIM_KEY, "[\"b\", \"c\"]");
		}};
		
		String jwsValid4 = oidcProviderMock.getIdToken(claimsIssuer4);
		chipsterToken = resource.createTokenFromOidc(jwsValid4, null);
		assertEquals(PREFIX4, new UserId(parseChipsterToken(chipsterToken).get("sub").toString()).getAuth());
		
		// issuer 5 requires both of "a" and "b"
		HashMap<String, Object> claimsIssuer5 = new HashMap<>(claimsIssuer3) {{
			put("aud", CLIENT_ID5);
			put("iss", ISSUER5);
			put(REQUIRED_CLAIM_KEY, REQUIRED_CLAIM_VALUE_JSON);
		}};
		
		String jwsValid5 = oidcProviderMock.getIdToken(claimsIssuer5);
		chipsterToken = resource.createTokenFromOidc(jwsValid5, null);
		assertEquals(PREFIX5, new UserId(parseChipsterToken(chipsterToken).get("sub").toString()).getAuth());
		
		try {
			// issuer 4 requires any of "a" or "b"
			HashMap<String, Object> claimsIssuer4Fail = new HashMap<>(claimsIssuer4) {{
				put(REQUIRED_CLAIM_KEY, "[\"c\"]");
			}};
			
			String jwsFail4 = oidcProviderMock.getIdToken(claimsIssuer4Fail);
			chipsterToken = resource.createTokenFromOidc(jwsFail4, null);
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		try {
			// issuer 5 requires both of "a" and "b"
			HashMap<String, Object> claimsIssuer5Fail = new HashMap<>(claimsIssuer5) {{
				put(REQUIRED_CLAIM_KEY, "[\"a\"]");
			}};
			
			String jwsFail5 = oidcProviderMock.getIdToken(claimsIssuer5Fail);
			chipsterToken = resource.createTokenFromOidc(jwsFail5, null);
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// wrong required claim value
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claimsIssuer2) {{				
				put(REQUIRED_CLAIM_KEY2, "wrong-value");
			}};
			String jws2 = oidcProviderMock.getIdToken(claims2);
			resource.createTokenFromOidc(jws2, null);				
			Assert.fail();
		} catch (ForbiddenException e) { }
				
		// wrong iss
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claimsIssuer3) {{				
				put("iss", "wrong-iss");
			}};
			String jws2 = oidcProviderMock.getIdToken(claims2);
			resource.createTokenFromOidc(jws2, null);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// wrong aud
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claimsIssuer3) {{				
				put("aud", "wrong-aud");
			}};
			String jws2 = oidcProviderMock.getIdToken(claims2);
			resource.createTokenFromOidc(jws2, null);				
			Assert.fail();
		} catch (ForbiddenException e) { }

		// missing sub (issuer3 uses this default)
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claimsIssuer3) {{
				remove("sub");
			}};
			String jws2 = oidcProviderMock.getIdToken(claims2);
			resource.createTokenFromOidc(jws2, null);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// missing userId claim (issuer1 is configured to get the userId from USER_ID_CLAIM_KEY)
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claimsIssuer1) {{
				remove(USER_ID_CLAIM_KEY);
			}};
			String jws2 = oidcProviderMock.getIdToken(claims2);
			resource.createTokenFromOidc(jws2, null);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// missing required claim (issuer1 is configured to require REQUIRE_CLAIM_KEY1)
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claimsIssuer1) {{
				remove(REQUIRED_CLAIM_KEY1);
			}};
			String jws2 = oidcProviderMock.getIdToken(claims2);
			resource.createTokenFromOidc(jws2, null);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// expired
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claimsIssuer3) {{				
				put("exp", Instant.now().getEpochSecond() - 1000);
			}};
			String jws2 = oidcProviderMock.getIdToken(claims2);
			resource.createTokenFromOidc(jws2, null);						
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// from future
		try {
			HashMap<String, Object> claims2 = new HashMap<>(claimsIssuer3) {{				
				put("iat", Instant.now().getEpochSecond() + 1000);
			}};
			String jws2 = oidcProviderMock.getIdToken(claims2);
			resource.createTokenFromOidc(jws2, null);
			Assert.fail();
		} catch (ForbiddenException e) { }

		// wrong key
		try {
			String jws2 = oidcProviderMock.getIdToken(privateKey2, claimsIssuer3, JWSAlgorithm.RS256);
			resource.createTokenFromOidc(jws2, null);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		/* 
		 * Try to test if symmetric or none algorithms are allowed
		 * https://auth0.com/blog/critical-vulnerabilities-in-json-web-token-libraries/
		 * 
		 * The Nimbus library makes it really difficult to make these kind of errors, I wasn't even able to test
		 * if the following two test really work. Let's try anyway:
		 */
		
		// signed with public key and symmetric algorithm. 
		try {
			String jws2 = getIdTokenSymmetric(oidcProviderMock.toString(), claimsIssuer3, JWSAlgorithm.HS256);
			resource.createTokenFromOidc(jws2, null);				
			Assert.fail();
		} catch (ForbiddenException e) { }
		
		// signed with public key and none algorithm
		try {
			String jws2 = getIdTokenNone(claimsIssuer3);
			resource.createTokenFromOidc(jws2, null);				
			Assert.fail();
		} catch (ForbiddenException e) { }
	}
	
	
	@SuppressWarnings("unchecked")
	private HashMap<String, Object> parseChipsterToken(String chipsterToken) {
		try {
			JWSObject plainObject = JWSObject.parse(chipsterToken);
			
			return RestUtils.parseJson(HashMap.class, plainObject.getPayload().toString());
			
		} catch (java.text.ParseException e) {
		    throw new RuntimeException("chipster token parse error", e);
		}
	}

	private static ArrayList<OidcConfig> getTestOidcConfigs() {
	
		Config config = new Config();
		
		OidcConfig oidc1 = OidcProviders.getOidcConfig("oidcName1", config);
		OidcConfig oidc2 = OidcProviders.getOidcConfig("oidcName2", config);
		OidcConfig oidc3 = OidcProviders.getOidcConfig("oidcName3", config);
		OidcConfig oidc4 = OidcProviders.getOidcConfig("oidcName4", config);
		OidcConfig oidc5 = OidcProviders.getOidcConfig("oidcName5", config);

		// oidc1 and oidc2 have same issuer and clientId, but different required claim
		oidc1.setIssuer(ISSUER12);
		oidc1.setClientId(CLIENT_ID12);
		oidc1.setClaimUserId(USER_ID_CLAIM_KEY);
		oidc1.setRequiredClaimKey(REQUIRED_CLAIM_KEY1);
		oidc1.setUserIdPrefix(PREFIX1);
		
		oidc2.setIssuer(ISSUER12);
		oidc2.setClientId(CLIENT_ID12);
		oidc2.setOidcName("oidcName2");
		oidc2.setClaimUserId(USER_ID_CLAIM_KEY);
		oidc2.setRequiredClaimKey(REQUIRED_CLAIM_KEY2);
		oidc2.setRequiredClaimValue(REQUIRED_CLAIM_VALUE2);
		oidc2.setUserIdPrefix(PREFIX2);
				
		// oidc3 is alone
		oidc3.setIssuer(ISSUER3);
		oidc3.setClientId("clientId3");
		oidc3.setOidcName("oidcName3");
		oidc3.setUserIdPrefix(PREFIX3);
		
		// oidc4
		oidc4.setIssuer(ISSUER4);
		oidc4.setClientId("clientId4");
		oidc4.setOidcName("oidcName4");
		oidc4.setRequiredClaimKey(REQUIRED_CLAIM_KEY);
		oidc4.setRequiredClaimValue(REQUIRED_CLAIM_VALUE_JSON);
		oidc4.setRequiredClaimValueComparison(OidcResource.COMPARISON_JSON_ARRAY_ANY);
		oidc4.setUserIdPrefix(PREFIX4);
		
		// oidc5
		oidc5.setIssuer(ISSUER5);
		oidc5.setClientId("clientId5");
		oidc5.setOidcName("oidcName5");
		oidc5.setRequiredClaimKey(REQUIRED_CLAIM_KEY);
		oidc5.setRequiredClaimValue(REQUIRED_CLAIM_VALUE_JSON);
		oidc5.setRequiredClaimValueComparison(OidcResource.COMPARISON_JSON_ARRAY_ALL);
		oidc5.setUserIdPrefix(PREFIX5);
						
		ArrayList<OidcConfig> oidcConfigs = new ArrayList<OidcConfig>() {{
			add(oidc1);
			add(oidc2);
			add(oidc3);
			add(oidc4);
			add(oidc5);
		}};		
		
		return oidcConfigs;
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
	
	private String getIdTokenNone(HashMap<String, Object> claims) throws KeyLengthException, JOSEException, ParseException {

		// Create the header
		PlainHeader header = new PlainHeader();

		JWTClaimsSet payload = JWTClaimsSet.parse(RestUtils.asJson(claims));

		// Create the JWE object and encrypt it
		PlainJWT jwsObject = new PlainJWT(header, payload);
		
		return jwsObject.serialize();
	}
}
