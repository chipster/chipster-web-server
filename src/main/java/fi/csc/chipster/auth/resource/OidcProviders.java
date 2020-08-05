package fi.csc.chipster.auth.resource;

import java.util.ArrayList;

import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.rest.Config;

/**
 * Interface for communicating with the OIDC provider
 * 
 * To allow it to be mocked for JUnit tests. 
 * 
 * @author klemela
 */
public interface OidcProviders {
	
	public static final String CONF_ISSUER = "auth-oidc-issuer";
	public static final String CONF_CLIENT_ID = "auth-oidc-client-id";
	public static final String CONF_CLIENT_SECRET = "auth-oidc-client-secret";
	public static final String CONF_REDIRECT_URI = "auth-oidc-redirect-path";
	public static final String CONF_RESPONSE_TYPE = "auth-oidc-response-type";	
	public static final String CONF_LOGO = "auth-oidc-logo";
	public static final String CONF_LOGO_WIDTH = "auth-oidc-logo-width";
	public static final String CONF_TEXT = "auth-oidc-text";
	public static final String CONF_PRIORITY = "auth-oidc-priority";
	public static final String CONF_VERIFIED_EMAIL_ONLY = "auth-oidc-verified-email-only";
	public static final String CONF_CLAIM_ORGANIZATION = "auth-oidc-claim-organization";
	public static final String CONF_CLAIM_USER_ID = "auth-oidc-claim-user-id";
	public static final String CONF_PARAMETER = "auth-oidc-parameter";
	public static final String CONF_USER_ID_PREFIX = "auth-oidc-user-id-prefix";
	public static final String CONF_APP_ID = "auth-oidc-app-id";
	public static final String CONF_REQUIRED_CLAIM_KEY = "auth-oidc-required-claim-key";
	public static final String CONF_REQUIRED_CLAIM_VALUE = "auth-oidc-required-claim-value";
	public static final String CONF_REQUIRED_CLAIM_VALUE_COMPARISON = "auth-oidc-required-claim-value-comparison";
	public static final String CONF_REQUIRED_USERINFO_CLAIM_KEY = "auth-oidc-required-userinfo-claim-key";	
	public static final String CONF_REQUIRED_USERINFO_CLAIM_VALUE = "auth-oidc-required-userinfo-claim-value";
	public static final String CONF_REQUIRED_USERINFO_CLAIM_VALUE_COMPARISON = "auth-oidc-required-userinfo-claim-value-comparison";
	public static final String CONF_REQUIRED_USERINFO_CLAIM_ERROR = "auth-oidc-required-userinfo-claim-error";
	public static final String CONF_DESCRIPTION = "auth-oidc-description";
	public static final String CONF_SCOPE = "auth-oidc-scope";

	ArrayList<OidcConfig> getOidcConfigs();

	UserInfo getUserInfo(OidcConfig oidcConfig, String accessTokenString, boolean isDebug);

	IDTokenValidator getValidator(OidcConfig oidcConfig);
	
	public static OidcConfig getOidcConfig(String oidcName, Config config) {
		
		OidcConfig oidc = new OidcConfig();
		
		oidc.setIssuer(config.getString(CONF_ISSUER, oidcName));
		oidc.setClientId(config.getString(CONF_CLIENT_ID, oidcName));
		oidc.setRedirectPath(config.getString(CONF_REDIRECT_URI, oidcName));
		oidc.setResponseType(config.getString(CONF_RESPONSE_TYPE, oidcName));
		oidc.setLogo(config.getString(CONF_LOGO, oidcName));
		oidc.setLogoWidth(config.getString(CONF_LOGO_WIDTH, oidcName));
		oidc.setText(config.getString(CONF_TEXT, oidcName));
		oidc.setPriority(Integer.parseInt(config.getString(CONF_PRIORITY, oidcName)));
		oidc.setVerifiedEmailOnly(config.getBoolean(CONF_VERIFIED_EMAIL_ONLY, oidcName));
		oidc.setOidcName(oidcName);
		oidc.setClaimOrganization(config.getString(CONF_CLAIM_ORGANIZATION, oidcName));
		oidc.setClaimUserId(config.getString(CONF_CLAIM_USER_ID, oidcName));
		oidc.setParameter(config.getString(CONF_PARAMETER, oidcName));
		oidc.setUserIdPrefix(config.getString(CONF_USER_ID_PREFIX, oidcName));
		oidc.setAppId(config.getString(CONF_APP_ID, oidcName));
		oidc.setRequiredClaimKey(config.getString(CONF_REQUIRED_CLAIM_KEY, oidcName));
		oidc.setRequiredClaimValue(config.getString(CONF_REQUIRED_CLAIM_VALUE, oidcName));
		oidc.setRequiredClaimValueComparison(config.getString(CONF_REQUIRED_CLAIM_VALUE_COMPARISON, oidcName));
		oidc.setRequiredUserinfoClaimKey(config.getString(CONF_REQUIRED_USERINFO_CLAIM_KEY, oidcName));
		oidc.setRequiredUserinfoClaimValue(config.getString(CONF_REQUIRED_USERINFO_CLAIM_VALUE, oidcName));
		oidc.setRequiredUserinfoClaimValueComparison(config.getString(CONF_REQUIRED_USERINFO_CLAIM_VALUE_COMPARISON, oidcName));
		oidc.setRequiredUserinfoClaimError(config.getString(CONF_REQUIRED_USERINFO_CLAIM_ERROR, oidcName));
		oidc.setDescription(config.getString(CONF_DESCRIPTION, oidcName));
		oidc.setScope(config.getString(CONF_SCOPE, oidcName));
		
		return oidc;
	}
}
