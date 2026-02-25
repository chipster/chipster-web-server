package fi.csc.chipster.auth;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;

import fi.csc.chipster.auth.resource.OidcConfig;
import fi.csc.chipster.auth.resource.OidcProviders;
import fi.csc.chipster.rest.RestUtils;
import net.minidev.json.JSONObject;

public class OidcProvidersMock extends OidcProviders {

	private RSAKey privateKey;
	private UserInfo nextUserInfo;

	public OidcProvidersMock(ArrayList<OidcConfig> oidcConfigs) throws MalformedURLException {

		try {
			this.privateKey = new RSAKeyGenerator(2048).generate();
		} catch (JOSEException e) {
			throw new RuntimeException("test error", e);
		}

		for (OidcConfig oidcConfig : oidcConfigs) {
			super.addOidcConfig(oidcConfig, null, new JWKSet(privateKey));
		}

	}

	/**
	 * Get private key for tests
	 * 
	 * @return
	 */
	public RSAKey getPrivateKey() {
		return privateKey;
	}

	public JWT getIdToken(HashMap<String, Object> claims) throws JOSEException, ParseException {
		return this.getIdToken(this.privateKey, claims, JWSAlgorithm.RS256);
	}

	protected JWT getIdToken(RSAKey privateKey, HashMap<String, Object> claims, JWSAlgorithm algorithm)
			throws JOSEException, ParseException {

		// https://connect2id.com/products/nimbus-jose-jwt/examples/jws-with-rsa-signature

		// Create RSA-signer with the private key
		JWSSigner signer = new RSASSASigner(privateKey);

		JWSHeader header = new JWSHeader.Builder(algorithm)
				.keyID(privateKey.getKeyID())
				.build();

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

		return JWTParser.parse(jws);
	}

	@Override
	public UserInfo getUserInfo(AccessToken accessToken, String oidcName) {

		if (this.nextUserInfo == null) {
			// return empty userInfo for tests that don't care about it
			return new UserInfo(new Subject());
		} else {
			UserInfo userInfo = this.nextUserInfo;
			// next tests should again get the default
			this.nextUserInfo = null;
			return userInfo;
		}
	}

	@Override
	protected ArrayList<OidcConfig> getPublicOidcConfigs() {
		throw new UnsupportedOperationException("Unimplemented method 'getPublicOidcConfigs'");
	}

	@Override
	protected String getAuthorizationEndpointURI(String oidcName) {
		throw new UnsupportedOperationException("Unimplemented method 'getAuthorizationEndpointURI'");
	}

	@Override
	protected URI getTokenEndpoint(String oidcName) {
		throw new UnsupportedOperationException("Unimplemented method 'getTokenEndpoint'");
	}

	/**
	 * Set a userInfo claims to be returned from next call to getUserInfo()
	 * 
	 * @param claims
	 */
	public void setNextUserInfo(HashMap<String, Object> claims) {
		this.nextUserInfo = new UserInfo(new JSONObject(claims));
	}
}
