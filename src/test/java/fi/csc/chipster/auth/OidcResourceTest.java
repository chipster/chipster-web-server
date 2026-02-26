package fi.csc.chipster.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.openid.connect.sdk.Nonce;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.auth.resource.OidcConfig;
import fi.csc.chipster.auth.resource.OidcLoginSessionsInDb;
import fi.csc.chipster.auth.resource.OidcProviders;
import fi.csc.chipster.auth.resource.OidcResource;
import fi.csc.chipster.auth.resource.UserTable;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import jakarta.ws.rs.ForbiddenException;

public class OidcResourceTest {

	private static final String OIDC_NAME_SIMPLE = "oidcNameSimple";
	private static final String OIDC_NAME_SHARED1 = "oidcNameShared1";
	private static final String OIDC_NAME_SHARED2 = "oidcNameShared2";
	private static final String OIDC_NAME_OR = "oidcNameOr";
	private static final String OIDC_NAME_AND = "oidcNameAnd";
	private static final String OIDC_NAME_USERINFO = "oidcNameUserinfo";

	private static final String PREFIX_SIMPLE = "prefixSimple";
	private static final String PREFIX_SHARED1 = "prefixShared1";
	private static final String PREFIX_SHARED2 = "prefixShared2";
	private static final String PREFIX_OR = "prefixOr";
	private static final String PREFIX_AND = "prefixAnd";
	private static final String PREFIX_USERINFO = "prefixUserinfo";

	private static final String ISSUER_SIMPLE = "issuerSimple";
	private static final String ISSUER_SHARED = "issuerShared";
	private static final String ISSUER_OR = "issuerOr";
	private static final String ISSUER_AND = "issuerAnd";
	private static final String ISSUER_USERINFO = "issuerUserinfo";

	private static final String CLIENT_ID_SIMPLE = "clientIdSimple";
	private static final String CLIENT_ID_SHARED = "clientIdShared";
	private static final String CLIENT_ID_OR = "clientIdOr";
	private static final String CLIENT_ID_AND = "clientIdAnd";
	private static final String CLIENT_ID_USER_INFO = "clientIdUserInfo";

	private static final String USER_ID_CLAIM_KEY = "userIdClaimKey";
	private static final String REQUIRED_CLAIM_KEY1 = "requiredClaimKey1";
	private static final String REQUIRED_CLAIM_KEY2 = "requiredClaimKey2";
	private static final String REQUIRED_CLAIM_KEY = "requiredClaimKey";
	private static final String REQUIRED_CLAIM_VALUE2 = "requiredClaimValue2";
	private static final String REQUIRED_CLAIM_VALUE_JSON = "[\"a\", \"b\"]";

	private static TestServerLauncher launcher;
	private static HibernateUtil hibernate;
	private static OidcProvidersMock oidcProviderMock;
	private static OidcResource oidcResource;

	private volatile static int userIdIndex = 1;

	private static String nonce;

	@BeforeAll
	public static void setUp() throws Exception {
		Config config = new Config();
		launcher = new TestServerLauncher(config);

		// let's use the real UserTable and AuthTable because other tests require the
		// backend anyway alternatively we could mock those too
		hibernate = new HibernateUtil(config, Role.AUTH,
				AuthenticationService.hibernateClasses);
		AuthTokens tokenTable = new AuthTokens(config);
		UserTable userTable = new UserTable(hibernate);

		oidcProviderMock = new OidcProvidersMock(getTestOidcConfigs());

		// in real use each request should have a different nonce, but for tests it
		// doesn't matter if use the same one
		nonce = new Nonce().getValue();

		// let's use real OidcLoginSessionsInDb because other tests require the DB
		// anyway. Alternatively OidcLoginSessionsInMemory could be used
		oidcResource = new OidcResource(oidcProviderMock, new OidcLoginSessionsInDb(config, hibernate));
		oidcResource.init(tokenTable, userTable, config);
	}

	@AfterAll
	public static void tearDown() throws Exception {
		launcher.stop();
	}

	public HashMap<String, Object> testClaims(HashMap<String, Object> claims, String oidcName,
			boolean successExpected) {
		return this.testClaims(claims, oidcName, successExpected, nonce);
	}

	public HashMap<String, Object> testClaims(HashMap<String, Object> claims, String oidcName,
			boolean successExpected, String nonce) {

		OidcConfig oidcConfig = oidcProviderMock.getOidcConfig(oidcName);

		// OidcResource methods are expected to be run inside a DB transaction
		return hibernate.runInTransaction(new HibernateRunnable<HashMap<String, Object>>() {

			@Override
			public HashMap<String, Object> run(Session hibernateSession) {
				try {
					JWT jws = oidcProviderMock.getIdToken(claims);
					String chipsterToken = oidcResource.createTokenFromOidc(oidcConfig, jws, null, nonce);
					if (!successExpected) {
						Assertions.fail("test should have thrown an exception");
					}
					return parseChipsterToken(chipsterToken);
				} catch (ForbiddenException e) {
					if (successExpected) {
						Assertions.fail("test failed with an exception", e);
					}
				} catch (Exception e) {
					// test always fails if any other exceptions is thrown
					Assertions.fail("test failed", e);
				}
				return null;
			}
		});
	}

	/**
	 * Test authentication with sipmle OIDC configuration
	 */
	@Test
	public void validSimple() {

		String oidcName = OIDC_NAME_SIMPLE;

		HashMap<String, Object> claims = new HashMap<String, Object>(getValidClaims(oidcName));

		// uncomment this if you want to verify that test fails
		// claims.remove("sub");

		HashMap<String, Object> chipsterToken = testClaims(claims, oidcName, true);

		assertEquals(PREFIX_SIMPLE, new UserId(chipsterToken.get("sub").toString()).getAuth());
	}

	/**
	 * Test authentication when we have two configurations with the same issuer and
	 * clientId
	 * 
	 * This is unlikely to cause problems anymore when the configuration is
	 * searched with oidcName, but let's keep it anyway to make sure that we don't
	 * breakt it.
	 */
	@Test
	public void validShared1() {

		String oidcName = OIDC_NAME_SHARED1;

		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName));

		// uncomment this if you want to verify that test fails
		// claimsIssuer1.remove("sub");

		HashMap<String, Object> chipsterToken = testClaims(claims, oidcName, true);

		assertEquals(PREFIX_SHARED1, new UserId(chipsterToken.get("sub").toString()).getAuth());
	}

	@Test
	public void validShared2() {

		String oidcName = OIDC_NAME_SHARED2;

		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName));

		// uncomment this if you want to verify that test fails
		// claimsIssuer2.remove(USER_ID_CLAIM_KEY);

		HashMap<String, Object> chipsterToken = testClaims(claims, oidcName, true);

		assertEquals(PREFIX_SHARED2, new UserId(chipsterToken.get("sub").toString()).getAuth());
	}

	/**
	 * Test that claims are found from userInfo
	 */
	@Test
	public void validUserInfo() {

		// userInfo is enabled for oidcName6
		String oidcName = OIDC_NAME_USERINFO;

		HashMap<String, Object> idTokenClaims = getValidClaims(oidcName);

		// remove the claim from id_token
		idTokenClaims.remove(REQUIRED_CLAIM_KEY1);

		oidcProviderMock.setNextUserInfo(new HashMap<String, Object>() {
			{
				put("sub", idTokenClaims.get("sub"));
				put(REQUIRED_CLAIM_KEY1, "any-value");
			}
		});

		HashMap<String, Object> chipsterToken = testClaims(idTokenClaims, oidcName, true);

		assertEquals(PREFIX_USERINFO, new UserId(chipsterToken.get("sub").toString()).getAuth());
	}

	@Test
	public void or() {

		String oidcName = OIDC_NAME_OR;

		// issuer 4 requires any of "a" or "b"
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName));

		// uncomment this if you want to verify that test fails
		// claims.remove(REQUIRED_CLAIM_KEY);

		HashMap<String, Object> chipsterToken = testClaims(claims, oidcName, true);

		assertEquals(PREFIX_OR, new UserId(chipsterToken.get("sub").toString()).getAuth());
	}

	@Test
	public void and() {

		String oidcName = OIDC_NAME_AND;

		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName));

		// uncomment this if you want to verify that test fails
		// claims.remove(REQUIRED_CLAIM_KEY);

		HashMap<String, Object> chipsterToken = testClaims(claims, oidcName, true);

		assertEquals(PREFIX_AND, new UserId(chipsterToken.get("sub").toString()).getAuth());
	}

	@Test
	public void orFail() {

		String oidcName = OIDC_NAME_OR;

		// issuer 4 requires any of "a" or "b"
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName)) {
			{
				put(REQUIRED_CLAIM_KEY, "[\"c\"]");
			}
		};

		testClaims(claims, oidcName, false);
	}

	@Test
	public void andFail() {

		String oidcName = OIDC_NAME_AND;

		// issuer 5 requires both of "a" and "b"
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName)) {
			{
				put(REQUIRED_CLAIM_KEY, "[\"a\"]");
			}
		};

		testClaims(claims, oidcName, false);
	}

	@Test
	public void wrongRequiredClaimValue() {

		String oidcName = OIDC_NAME_SHARED2;

		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName)) {
			{
				put(REQUIRED_CLAIM_KEY2, "wrong-value");
			}
		};
		testClaims(claims, oidcName, false);
	}

	@Test
	public void wrongIss() {

		String oidcName = OIDC_NAME_SIMPLE;
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName)) {
			{
				put("iss", "wrong-iss");
			}
		};

		testClaims(claims, oidcName, false);
	}

	@Test
	public void wrongAud() {

		String oidcName = OIDC_NAME_SIMPLE;
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName)) {
			{
				put("aud", "wrong-aud");
			}
		};

		testClaims(claims, oidcName, false);
	}

	// missing sub (issuer3 uses this default)
	@Test
	public void missingSub() {

		String oidcName = OIDC_NAME_SIMPLE;
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName)) {
			{
				remove("sub");
			}
		};

		testClaims(claims, oidcName, false);
	}

	// missing userId claim (issuer1 is configured to get the userId from
	// USER_ID_CLAIM_KEY)
	@Test
	public void missingUserIdClaim() {

		String oidcName = OIDC_NAME_SHARED1;
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName)) {
			{
				remove(USER_ID_CLAIM_KEY);
			}
		};

		testClaims(claims, oidcName, false);
	}

	// missing required claim (issuer1 is configured to require REQUIRE_CLAIM_KEY1
	@Test
	public void missingRequiredClaim() {

		String oidcName = OIDC_NAME_SHARED1;
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName)) {
			{
				remove(REQUIRED_CLAIM_KEY1);
			}
		};

		testClaims(claims, oidcName, false);
	}

	@Test
	public void expired() {

		String oidcName = OIDC_NAME_SIMPLE;
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName)) {
			{
				put("exp", Instant.now().getEpochSecond() - 1000);
			}
		};

		testClaims(claims, oidcName, false);
	}

	@Test
	public void fromFuture() {

		String oidcName = OIDC_NAME_SIMPLE;
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName)) {
			{
				put("iat", Instant.now().getEpochSecond() + 1000);
			}
		};

		testClaims(claims, oidcName, false);
	}

	@Test
	public void wrongNonce() {

		String oidcName = OIDC_NAME_SIMPLE;
		HashMap<String, Object> claims = new HashMap<>(getValidClaims(oidcName));

		testClaims(claims, oidcName, false, new Nonce().getValue());
	}

	/**
	 * If userId is not found from id_token, it should be searched from userInfo
	 */
	@Test
	public void userIdFromUserInfo() {

		String oidcName = OIDC_NAME_USERINFO;

		HashMap<String, Object> idTokenClaims = new HashMap<>(getValidClaims(oidcName));

		String userId = (String) idTokenClaims.remove(USER_ID_CLAIM_KEY);

		oidcProviderMock.setNextUserInfo(new HashMap<>() {
			{
				put("sub", idTokenClaims.get("sub"));
				put(USER_ID_CLAIM_KEY, userId);
			}
		});

		HashMap<String, Object> chipsterToken = testClaims(idTokenClaims, oidcName, true);

		assertEquals(userId, new UserId(chipsterToken.get("sub").toString()).getUsername());
	}

	/**
	 * If the same claim has different value in id_token and userInfo, use the value
	 * from id_token. I don't know why the authentication server would do this, but
	 * let's be at least consistent if this happens
	 */
	@Test
	public void claimPriorityOfUserInfo() {

		String oidcName = OIDC_NAME_USERINFO;

		HashMap<String, Object> idTokenClaims = new HashMap<>(getValidClaims(oidcName));

		String userId = (String) idTokenClaims.get("sub");
		idTokenClaims.put(USER_ID_CLAIM_KEY, userId + "fromIdToken");

		oidcProviderMock.setNextUserInfo(new HashMap<>() {
			{
				put("sub", idTokenClaims.get("sub"));
				put(USER_ID_CLAIM_KEY, userId + "fromUserInfo");
			}
		});

		HashMap<String, Object> chipsterToken = testClaims(idTokenClaims, oidcName, true);

		assertEquals(userId + "fromIdToken", new UserId(chipsterToken.get("sub").toString()).getUsername());
	}

	// wrong key
	@Test
	public void wrongKey() throws JOSEException, ParseException {

		String oidcName = OIDC_NAME_SIMPLE;
		OidcConfig oidcConfig = oidcProviderMock.getOidcConfig(oidcName);

		hibernate.runInTransaction(new HibernateRunnable<Void>() {

			@Override
			public Void run(Session hibernateSession) {
				try {

					// wrong key
					RSAKey privateKey = new RSAKeyGenerator(2048).generate();

					// correct key
					// RSAKey privateKey = oidcProviderMock.getPrivateKey();

					JWT jws = oidcProviderMock.getIdToken(privateKey, getValidClaims(oidcName), JWSAlgorithm.RS256);

					oidcResource.createTokenFromOidc(oidcConfig, jws, null, nonce);
					Assertions.fail();
				} catch (ForbiddenException e) {
					// expected
				} catch (Exception e) {
					// test always fails if any other exceptions is thrown
					Assertions.fail("test failed", e);
				}
				return null;
			}
		});
	}

	/*
	 * Try to test if symmetric or none algorithms are allowed
	 *
	 * https://auth0.com/blog/critical-vulnerabilities-in-json-web-token-libraries/
	 *
	 * The Nimbus library makes it really difficult to make these kind of errors,
	 * I wasn't even able to test if the following two test really work. Let's try
	 * anyway:
	 */

	// signed with public key and ssymmetric algorithm.

	@Test
	public void symmetricAlgorithm() throws KeyLengthException, JOSEException, ParseException {

		String oidcName = OIDC_NAME_SIMPLE;
		OidcConfig oidcConfig = oidcProviderMock.getOidcConfig(oidcName);

		hibernate.runInTransaction(new HibernateRunnable<Void>() {

			@Override
			public Void run(Session hibernateSession) {
				try {
					JWT jws = getIdTokenSymmetric(oidcProviderMock.toString(),
							getValidClaims(oidcName), JWSAlgorithm.HS256);
					oidcResource.createTokenFromOidc(oidcConfig, jws, null, nonce);
					Assertions.fail();
				} catch (ForbiddenException e) {
					// expected
				} catch (Exception e) {
					// test always fails if any other exceptions is thrown
					Assertions.fail("test failed", e);
				}
				return null;
			}
		});
	}

	@Test
	public void noneAlgorithm() throws KeyLengthException, JOSEException, ParseException {

		String oidcName = OIDC_NAME_SIMPLE;
		OidcConfig oidcConfig = oidcProviderMock.getOidcConfig(oidcName);

		hibernate.runInTransaction(new HibernateRunnable<Void>() {

			@Override
			public Void run(Session hibernateSession) {
				// signed with public key and none algorithm
				try {
					JWT jws = getIdTokenNone(getValidClaims(oidcName));
					oidcResource.createTokenFromOidc(oidcConfig, jws, null, nonce);
					Assertions.fail();
				} catch (ForbiddenException e) {
					// expected
				} catch (Exception e) {
					// test always fails if any other exceptions is thrown
					Assertions.fail("test failed", e);
				}
				return null;
			}
		});
	}

	@SuppressWarnings("unchecked")
	private HashMap<String, Object> parseChipsterToken(String chipsterToken) {
		try {
			JWSObject plainObject = JWSObject.parse(chipsterToken);

			return RestUtils.parseJson(HashMap.class,
					plainObject.getPayload().toString());

		} catch (java.text.ParseException e) {
			throw new RuntimeException("chipster token parse error", e);
		}
	}

	private static ArrayList<OidcConfig> getTestOidcConfigs() {

		Config config = new Config();

		// get config defaults
		OidcConfig oidcShared1 = OidcProviders.getOidcConfig(OIDC_NAME_SHARED1, config);
		OidcConfig oidcShared2 = OidcProviders.getOidcConfig(OIDC_NAME_SHARED2, config);
		OidcConfig oidcSimple = OidcProviders.getOidcConfig(OIDC_NAME_SIMPLE, config);
		OidcConfig oidcOr = OidcProviders.getOidcConfig(OIDC_NAME_OR, config);
		OidcConfig oidcAnd = OidcProviders.getOidcConfig(OIDC_NAME_AND, config);
		OidcConfig oidcUserinfo = OidcProviders.getOidcConfig(OIDC_NAME_USERINFO, config);

		// simple configuration
		oidcSimple.setIssuer(ISSUER_SIMPLE);
		oidcSimple.setClientId(CLIENT_ID_SIMPLE);
		oidcSimple.setUserIdPrefix(PREFIX_SIMPLE);

		// these two have same issuer and clientId, but different required claim
		oidcShared1.setIssuer(ISSUER_SHARED);
		oidcShared1.setClientId(CLIENT_ID_SHARED);
		oidcShared1.setClaimUserId(USER_ID_CLAIM_KEY);
		oidcShared1.setRequiredClaimKey(REQUIRED_CLAIM_KEY1);
		oidcShared1.setUserIdPrefix(PREFIX_SHARED1);

		oidcShared2.setIssuer(ISSUER_SHARED);
		oidcShared2.setClientId(CLIENT_ID_SHARED);
		oidcShared2.setClaimUserId(USER_ID_CLAIM_KEY);
		oidcShared2.setRequiredClaimKey(REQUIRED_CLAIM_KEY2);
		oidcShared2.setRequiredClaimValue(REQUIRED_CLAIM_VALUE2);
		oidcShared2.setUserIdPrefix(PREFIX_SHARED2);

		// this needs that at least one of the required values is found
		oidcOr.setIssuer(ISSUER_OR);
		oidcOr.setClientId(CLIENT_ID_OR);
		oidcOr.setRequiredClaimKey(REQUIRED_CLAIM_KEY);
		oidcOr.setRequiredClaimValue(REQUIRED_CLAIM_VALUE_JSON);
		oidcOr.setRequiredClaimValueComparison(OidcResource.COMPARISON_JSON_ARRAY_ANY);
		oidcOr.setUserIdPrefix(PREFIX_OR);

		// this needs that all required values are found
		oidcAnd.setIssuer(ISSUER_AND);
		oidcAnd.setClientId(CLIENT_ID_AND);
		oidcAnd.setRequiredClaimKey(REQUIRED_CLAIM_KEY);
		oidcAnd.setRequiredClaimValue(REQUIRED_CLAIM_VALUE_JSON);
		oidcAnd.setRequiredClaimValueComparison(OidcResource.COMPARISON_JSON_ARRAY_ALL);
		oidcAnd.setUserIdPrefix(PREFIX_AND);

		// this queries also userInfo
		oidcUserinfo.setIssuer(ISSUER_USERINFO);
		oidcUserinfo.setClientId(CLIENT_ID_USER_INFO);
		oidcUserinfo.setClaimUserId(USER_ID_CLAIM_KEY);
		oidcUserinfo.setRequiredClaimKey(REQUIRED_CLAIM_KEY1);
		oidcUserinfo.setQueryUserInfo(true);
		oidcUserinfo.setUserIdPrefix(PREFIX_USERINFO);

		ArrayList<OidcConfig> oidcConfigs = new ArrayList<OidcConfig>() {
			{
				add(oidcShared1);
				add(oidcShared2);
				add(oidcSimple);
				add(oidcOr);
				add(oidcAnd);
				add(oidcUserinfo);
			}
		};

		return oidcConfigs;
	}

	public HashMap<String, Object> getBaseClaims() {
		return new HashMap<String, Object>() {
			{
				// db flush() gets sometimes stuck if all tests use the same UserId
				// many transactions try to update the same row?
				put("sub", "userId" + userIdIndex++);
				put("iat", Instant.now().getEpochSecond());
				put("exp", Instant.now().getEpochSecond() + 10);
				put("nonce", nonce);
			}
		};
	}

	public HashMap<String, Object> getValidClaims(String oidcName) {
		HashMap<String, Object> claims;
		switch (oidcName) {
			case OIDC_NAME_SIMPLE:
				return new HashMap<String, Object>(getBaseClaims()) {
					{
						put("iss", ISSUER_SIMPLE);
						put("aud", CLIENT_ID_SIMPLE);
					}
				};
			case OIDC_NAME_SHARED1:
				claims = new HashMap<String, Object>(getBaseClaims()) {
					{
						put("aud", CLIENT_ID_SHARED);
						put("iss", ISSUER_SHARED);
						put(USER_ID_CLAIM_KEY, "userIdValue");
						put(REQUIRED_CLAIM_KEY1, "anyClaimValue");
					}
				};
				claims.put(USER_ID_CLAIM_KEY, claims.get("sub") + "fromClaim");
				return claims;
			case OIDC_NAME_SHARED2:
				return new HashMap<String, Object>(getBaseClaims()) {
					{
						put(USER_ID_CLAIM_KEY, "userIdValue" + userIdIndex++);
						put("aud", CLIENT_ID_SHARED);
						put("iss", ISSUER_SHARED);
						put(REQUIRED_CLAIM_KEY2, REQUIRED_CLAIM_VALUE2);
					}
				};
			case OIDC_NAME_OR:
				return new HashMap<String, Object>(getBaseClaims()) {
					{
						put("aud", CLIENT_ID_OR);
						put("iss", ISSUER_OR);
						put(REQUIRED_CLAIM_KEY, "[\"b\", \"c\"]");
					}
				};
			case OIDC_NAME_AND:
				return new HashMap<String, Object>(getBaseClaims()) {
					{
						put("aud", CLIENT_ID_AND);
						put("iss", ISSUER_AND);
						put(REQUIRED_CLAIM_KEY, REQUIRED_CLAIM_VALUE_JSON);
					}
				};

			case OIDC_NAME_USERINFO:
				claims = new HashMap<String, Object>(getBaseClaims()) {
					{
						put("aud", CLIENT_ID_USER_INFO);
						put("iss", ISSUER_USERINFO);
						put(REQUIRED_CLAIM_KEY1, "any-value");

					}
				};
				claims.put(USER_ID_CLAIM_KEY, claims.get("sub") + "fromClaim");
				return claims;
			default:
				throw new RuntimeException("unknown oidcName " + oidcName);
		}
	}

	/**
	 * @param key
	 * @param claims
	 * @param algorithm
	 * @return
	 * @throws KeyLengthException
	 * @throws JOSEException
	 * @throws ParseException
	 */
	private JWT getIdTokenSymmetric(String key, HashMap<String, Object> claims, JWSAlgorithm algorithm)
			throws KeyLengthException, JOSEException, ParseException {

		// Create the header
		JWSHeader header = new JWSHeader(algorithm);

		// Set the plain text
		Payload payload = new Payload(RestUtils.asJson(claims));

		// Create the JWE object and encrypt it
		JWSObject jwsObject = new JWSObject(header, payload);
		MACSigner signer = new MACSigner(key);
		jwsObject.sign(signer);

		return JWTParser.parse(jwsObject.serialize());
	}

	private JWT getIdTokenNone(HashMap<String, Object> claims)
			throws KeyLengthException, JOSEException, ParseException {

		// Create the header
		PlainHeader header = new PlainHeader();

		JWTClaimsSet payload = JWTClaimsSet.parse(RestUtils.asJson(claims));

		// Create the JWE object and encrypt it
		PlainJWT jwsObject = new PlainJWT(header, payload);

		return JWTParser.parse(jwsObject.serialize());
	}
}
