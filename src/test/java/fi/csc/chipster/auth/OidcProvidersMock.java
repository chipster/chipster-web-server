package fi.csc.chipster.auth;

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
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.auth.resource.OidcConfig;
import fi.csc.chipster.auth.resource.OidcProviders;
import fi.csc.chipster.rest.RestUtils;

public class OidcProvidersMock implements OidcProviders {

	private ArrayList<OidcConfig> oidcConfigs;
	private RSAKey privateKey;

	public OidcProvidersMock(ArrayList<OidcConfig> oidcConfigs) {
		this.oidcConfigs = oidcConfigs;
		try {
			this.privateKey = new RSAKeyGenerator(2048).generate();
		} catch (JOSEException e) {
			throw new RuntimeException("test error", e);
		}
	}

	@Override
	public ArrayList<OidcConfig> getOidcConfigs() {
		return oidcConfigs;
	}

	@Override
	public UserInfo getUserInfo(OidcConfig oidcConfig, String accessTokenString, boolean isDebug) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public IDTokenValidator getValidator(OidcConfig oidcConfig) {
		return new IDTokenValidator(new Issuer(oidcConfig.getIssuer()), new ClientID(oidcConfig.getClientId()),
				JWSAlgorithm.RS256, new JWKSet(privateKey));
	}

	public RSAKey getPublicKey() {
		return privateKey.toPublicJWK();
	}

	public String getIdToken(HashMap<String, Object> claims) throws JOSEException {
		return this.getIdToken(this.privateKey, claims, JWSAlgorithm.RS256);
	}

	protected String getIdToken(RSAKey privateKey, HashMap<String, Object> claims, JWSAlgorithm algorithm)
			throws JOSEException {

		// https://connect2id.com/products/nimbus-jose-jwt/examples/jws-with-rsa-signature

		// Create RSA-signer with the private key
		JWSSigner signer = new RSASSASigner(privateKey);

		JWSHeader header = new JWSHeader.Builder(algorithm).keyID(privateKey.getKeyID()).build();
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
}
