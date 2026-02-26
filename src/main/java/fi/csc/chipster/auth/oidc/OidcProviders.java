package fi.csc.chipster.auth.oidc;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.rest.Config;
import jakarta.ws.rs.BadRequestException;

/**
 * Interface for communicating with the OIDC provider
 * 
 * To allow it to be mocked for JUnit tests.
 * 
 * @author klemela
 */
public abstract class OidcProviders {

	private static final Logger logger = LogManager.getLogger();

	public static final String CONF_ISSUER = "auth-oidc-issuer";
	public static final String CONF_CLIENT_ID = "auth-oidc-client-id";
	public static final String CONF_CLIENT_SECRET = "auth-oidc-client-secret";
	public static final String CONF_REDIRECT_URI = "auth-oidc-redirect-path";
	public static final String CONF_RESPONSE_TYPE = "auth-oidc-response-type";
	public static final String CONF_LOGO = "auth-oidc-logo";
	public static final String CONF_TEXT = "auth-oidc-text";
	public static final String CONF_PRIORITY = "auth-oidc-priority";
	public static final String CONF_VERIFIED_EMAIL_ONLY = "auth-oidc-verified-email-only";
	public static final String CONF_CLAIM_ORGANIZATION = "auth-oidc-claim-organization";
	public static final String CONF_CLAIM_USER_ID = "auth-oidc-claim-user-id";
	public static final String CONF_PARAMETER = "auth-oidc-parameter";
	public static final String CONF_USER_ID_PREFIX = "auth-oidc-user-id-prefix";
	public static final String CONF_REQUIRED_CLAIM_KEY = "auth-oidc-required-claim-key";
	public static final String CONF_REQUIRED_CLAIM_VALUE = "auth-oidc-required-claim-value";
	public static final String CONF_REQUIRED_CLAIM_VALUE_COMPARISON = "auth-oidc-required-claim-value-comparison";
	public static final String CONF_REQUIRED_CLAIM_ERROR = "auth-oidc-required-claim-error";
	public static final String CONF_QUERY_USERINFO = "auth-oidc-query-userinfo";
	public static final String CONF_DESCRIPTION = "auth-oidc-description";
	public static final String CONF_SCOPE = "auth-oidc-scope";
	public static final String CONF_JWS_ALGORITHM = "auth-oidc-jws-algorithm";

	private HashMap<String, OidcConfig> oidcConfigs = new HashMap<>();
	private HashMap<String, IDTokenValidator> validators = new HashMap<>();

	public IDTokenValidator getValidator(String oidcName) {
		return validators.get(oidcName);
	}

	public static OidcConfig getOidcConfig(String oidcName, Config config) {

		OidcConfig oidc = new OidcConfig();

		oidc.setIssuer(config.getString(CONF_ISSUER, oidcName));
		oidc.setClientId(config.getString(CONF_CLIENT_ID, oidcName));
		oidc.setClientSecret(config.getString(CONF_CLIENT_SECRET, oidcName));
		oidc.setRedirectPath(config.getString(CONF_REDIRECT_URI, oidcName));
		oidc.setResponseType(config.getString(CONF_RESPONSE_TYPE, oidcName));
		oidc.setLogo(config.getString(CONF_LOGO, oidcName));
		oidc.setText(config.getString(CONF_TEXT, oidcName));
		oidc.setPriority(Integer.parseInt(config.getString(CONF_PRIORITY, oidcName)));
		oidc.setVerifiedEmailOnly(config.getBoolean(CONF_VERIFIED_EMAIL_ONLY, oidcName));
		oidc.setOidcName(oidcName);
		oidc.setClaimOrganization(config.getString(CONF_CLAIM_ORGANIZATION, oidcName));
		oidc.setClaimUserId(config.getString(CONF_CLAIM_USER_ID, oidcName));
		oidc.setParameter(config.getString(CONF_PARAMETER, oidcName));
		oidc.setUserIdPrefix(config.getString(CONF_USER_ID_PREFIX, oidcName));
		oidc.setRequiredClaimKey(config.getString(CONF_REQUIRED_CLAIM_KEY, oidcName));
		oidc.setRequiredClaimValue(config.getString(CONF_REQUIRED_CLAIM_VALUE, oidcName));
		oidc.setRequiredClaimValueComparison(config.getString(CONF_REQUIRED_CLAIM_VALUE_COMPARISON, oidcName));
		oidc.setQueryUserInfo(config.getBoolean(CONF_QUERY_USERINFO));
		oidc.setRequiredClaimError(config.getString(CONF_REQUIRED_CLAIM_ERROR, oidcName));
		oidc.setDescription(config.getString(CONF_DESCRIPTION, oidcName));
		oidc.setScope(config.getString(CONF_SCOPE, oidcName));
		oidc.setJwsAlgorithm(config.getString(CONF_JWS_ALGORITHM));

		return oidc;
	}

	public abstract UserInfo getUserInfo(AccessToken accessToken, String oidcName);

	public abstract ArrayList<OidcConfig> getPublicOidcConfigs();

	public abstract String getAuthorizationEndpointURI(String oidcName);

	public abstract URI getTokenEndpoint(String oidcName);

	public HashMap<String, OidcConfig> getOidcConfigs() {
		return this.oidcConfigs;
	}

	public void addOidcConfig(OidcConfig oidcConfig, URI jwkSetUri, JWKSet jwkSet) throws MalformedURLException {
		this.oidcConfigs.put(oidcConfig.getOidcName(), oidcConfig);

		this.validators.put(oidcConfig.getOidcName(),
				createValidator(oidcConfig.getIssuer(), oidcConfig.getClientId(), jwkSetUri, jwkSet,
						oidcConfig.getJwsAlgorithm(), oidcConfig.getOidcName()));
	}

	public OidcConfig getOidcConfig(String oidcName) {
		OidcConfig oidcConfig = oidcConfigs.get(oidcName);

		if (oidcConfig == null) {
			throw new BadRequestException("unknown oidcName");
		}
		return oidcConfig;
	}

	public IDTokenValidator createValidator(String issuerString, String clientIdString, URI jwkSetURI, JWKSet jwkSet,
			String jwsAlgorithm, String oidcName) throws MalformedURLException {

		if (jwkSetURI == null && jwkSet == null) {
			throw new IllegalStateException(
					"OpenID Connect jwkSetURI and jwkSet are null, cannot verify login tokens without one of them");
		} else {
			logger.info("OIDC " + oidcName + " download OpenID Connect keys from " + jwkSetURI);
		}

		Issuer issuer = new Issuer(issuerString);
		ClientID clientID = new ClientID(clientIdString);
		JWSAlgorithm algorithm = new JWSAlgorithm(jwsAlgorithm, null);

		// Create validator for signed ID tokens
		if (jwkSetURI != null) {
			// it should download the token signing keys and keep them updated (e.g. daily
			// for google)
			return new IDTokenValidator(issuer, clientID, algorithm, jwkSetURI.toURL());
		} else {
			// give keys directly in tests
			return new IDTokenValidator(issuer, clientID, algorithm, jwkSet);
		}
	}
}
