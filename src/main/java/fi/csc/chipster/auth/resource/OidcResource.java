package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;
import com.nimbusds.openid.connect.sdk.UserInfoResponse;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderConfigurationRequest;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("oidc")
public class OidcResource {

	private static final Logger logger = LogManager.getLogger();
	
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
	public static final String CONF_REQUIRE_CLAIM = "auth-oidc-require-claim";
	public static final String CONF_REQUIRE_USERINFO_CLAIM = "auth-oidc-require-userinfo-claim";
	public static final String CONF_DESCRIPTION = "auth-oidc-description";
	public static final String CONF_SCOPE = "auth-oidc-scope";
	public static final String CONF_DEBUG = "auth-oidc-debug";
		
	private AuthTokens tokenTable;
	private UserTable userTable;

	private ArrayList<OidcConfig> sortedOidcConfigs = new ArrayList<>();	
	private HashMap<OidcConfig, IDTokenValidator> validators = new HashMap<>();	
	
	private HashMap<OidcConfig, URI> userInfoEndpointURIs = new HashMap<>();

	private boolean isDebug;

	public void init(AuthTokens tokenTable, UserTable userTable, Config config) throws URISyntaxException, IOException {
		this.tokenTable = tokenTable;
		this.userTable = userTable;
		this.isDebug = config.getBoolean(CONF_DEBUG);
		
		ArrayList<OidcConfig> oidcConfigs = new ArrayList<>();
				
		for (String oidcName : config.getConfigEntries(OidcResource.CONF_ISSUER + "-").keySet()) {
			String issuer = config.getString(CONF_ISSUER, oidcName);
			String clientId = config.getString(CONF_CLIENT_ID, oidcName);

			OidcConfig oidc = new OidcConfig();
			
			oidc.setIssuer(issuer);
			oidc.setClientId(clientId);
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
			oidc.setRequireClaim(config.getString(CONF_REQUIRE_CLAIM, oidcName));
			oidc.setRequireUserinfoClaim(config.getString(CONF_REQUIRE_USERINFO_CLAIM));
			oidc.setDescription(config.getString(CONF_DESCRIPTION, oidcName));
			oidc.setScope(config.getString(CONF_SCOPE, oidcName));
			 
			// use list, because there is no good map key. Multiple oidcConfigs may have the same issuer.
			oidcConfigs.add(oidc);
			
			OIDCProviderMetadata metadata;
			try {
				metadata = getMetadata(oidc);
			} catch (com.nimbusds.oauth2.sdk.ParseException e) {
				throw new RuntimeException("oidc metadata error " + oidc.getOidcName(), e);
			}
			
			// if multiple oidConfigs have the same issuer, they must have the same clientId
			// because before validation we know only the issuer
			this.validators.put(oidc, getValidator(issuer, clientId, metadata.getJWKSetURI(), null));			
			
			this.userInfoEndpointURIs.put(oidc, getUserInfoEndpointURI(oidc, metadata));
		}
	
		setOidcConfigs(oidcConfigs);		
	}
	
	protected void setOidcConfigs(ArrayList<OidcConfig> oidcConfigs) {
		
		sortedOidcConfigs = new ArrayList<OidcConfig>(oidcConfigs);
		sortedOidcConfigs.sort((a, b) -> a.getPriority().compareTo(b.getPriority()));
		
		for (OidcConfig oidc : sortedOidcConfigs) {			
			logger.info("OpenID Connect issuer " + oidc.getIssuer() + " enabled");
		}
	}
	
	private OIDCProviderMetadata getMetadata(OidcConfig oidc) throws IOException, com.nimbusds.oauth2.sdk.ParseException {
				
		// The OpenID provider issuer URL
		Issuer issuer = new Issuer(oidc.getIssuer());

		// Will resolve the OpenID provider metadata automatically
		OIDCProviderConfigurationRequest request = new OIDCProviderConfigurationRequest(issuer);

		// Make HTTP request
		HTTPRequest httpRequest = request.toHTTPRequest();
		HTTPResponse httpResponse = httpRequest.send();

		// Parse OpenID provider metadata
		return OIDCProviderMetadata.parse(httpResponse.getContentAsJSONObject());
	}
	
	
	private URI getUserInfoEndpointURI(OidcConfig oidc, OIDCProviderMetadata metadata) {
		URI uri = metadata.getUserInfoEndpointURI();
		
		if (!oidc.getRequireUserinfoClaim().isEmpty() && uri == null) {
			throw new IllegalStateException("OpenID Connect userinfo endpoint is null, cannot check required claims without it");
		}
		
		return uri;
	}

	protected IDTokenValidator getValidator(String issuerString, String clientIdString, URI jwkSetURI, JWKSet jwkSet) throws URISyntaxException, IOException {
				
		if (jwkSetURI == null && jwkSet == null) {
			throw new IllegalStateException("OpenID Connect jwk_uri is null, cannot verify login tokens without it");
		} else {
			logger.info("download OpenID Connect keys from " + jwkSetURI);
		}
		
		Issuer issuer = new Issuer(issuerString);
		ClientID clientID = new ClientID(clientIdString);
		JWSAlgorithm algorithm = JWSAlgorithm.RS256;

		// Create validator for signed ID tokens
		if (jwkSetURI != null) {
			// it should download the token signing keys and keep them updated (e.g. daily for google) 
			return new IDTokenValidator(issuer, clientID, algorithm, jwkSetURI.toURL());
		} else {
			// give keys directly in tests
			return new IDTokenValidator(issuer, clientID, algorithm, jwkSet);
		}
	}

	@POST
	@RolesAllowed(Role.UNAUTHENTICATED)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.TEXT_PLAIN)
	@Transaction
	public Response createTokenFromOidc(HashMap<String, String> json) throws GeneralSecurityException, IOException, ParseException {

		String idTokenString = json.get("idToken");
		String accessTokenString = json.get("accessToken");
		
		if (idTokenString == null) {
			throw new ForbiddenException("no ID token");
		}
				
		// https://developers.google.com/identity/sign-in/web/backend-auth
		// https://connect2id.com/blog/how-to-validate-an-openid-connect-id-token		
		
		// parse the ID token
		JWT idToken;
		try {
			idToken = JWTParser.parse(idTokenString);
		} catch (ParseException e) {
			logger.warn("invalid ID token", e);
			throw new ForbiddenException("ID token parsing failed");
		}

		if (this.isDebug) {
			logger.info("claims before validation: ");
			Map<String, Object> claims = idToken.getJWTClaimsSet().getClaims();
			for (String k : claims.keySet()) {
				logger.info("claim " + k + ": " + claims.get(k));
			}
		}
		
		// the OidcConfig is selected based on token data before it's validated
		// to find out which validator should to use
		String issuer = idToken.getJWTClaimsSet().getIssuer();
		String clientId = idToken.getJWTClaimsSet().getAudience().get(0);
		OidcConfig oidcConfig = getOidcConfig(issuer, clientId, idToken.getJWTClaimsSet().getClaims());
		
		IDTokenValidator validator = validators.get(oidcConfig);
		IDTokenClaimsSet claims = validateIdToken(idToken, validator);		
		
		// token is valid, we can trust that it came from the issuer
		
		String sub = claims.getSubject().getValue();	
		
		checkUserInfo(oidcConfig, accessTokenString, issuer, clientId, sub);
						
		String name = claims.getStringClaim("name");
		String email = claims.getStringClaim("email");
		Boolean emailVerified = claims.getBooleanClaim("email_verified");
				
		// use different auth names in Chipster based on the claims that we get
		String username = getClaim(oidcConfig.getClaimUserId(), claims);
		String userIdPrefix = oidcConfig.getUserIdPrefix();
		
		UserId userId = null;
		if (oidcConfig.getClaimUserId().equals("sub")) {
			userId = new UserId(userIdPrefix, sub);
			
		} else if (username != null) {
			userId = new UserId(userIdPrefix, username);
						
		} else {
			throw new ForbiddenException("username not found from claim " + oidcConfig.getClaimUserId());
		}
		
		// store only verified emails
		if (oidcConfig.getVerifiedEmailOnly() && (emailVerified == null || emailVerified == false)) {
			email = null;
		}
		
		String organization = getClaim(oidcConfig.getClaimOrganization(), claims);				
		User user = new User(userId.getAuth(), userId.getUsername(), email, organization, name);
				
		userTable.addOrUpdate(user);

		HashSet<String> roles = Stream.of(Role.CLIENT, Role.OIDC).collect(Collectors.toCollection(HashSet::new));
		String token = tokenTable.createNewToken(userId.toUserIdString(), roles, user.getName());

		return Response.ok(token).build();	
	}
	

	/**
	 * Do this in separate method to allow it to be tested without settings up OIDC server
	 * 
	 * @param idToken
	 * @param accessTokenString
	 * @param validator
	 * @return
	 */
	protected IDTokenClaimsSet validateIdToken(JWT idToken, IDTokenValidator validator) {
		
		IDTokenClaimsSet claims;
		try {
			// client can send anything 
			// we can trust this only after the signature, iss, sub, aud and ext are checked
			claims = validator.validate(idToken, null);
			
		} catch (BadJOSEException e) {
			// Invalid signature or claims (iss, aud, exp...)
			logger.warn("invalid ID token", e);
			throw new ForbiddenException("invalid ID token signature or claims");
		} catch (JOSEException e) {
			// Internal processing exception
			logger.warn("invalid ID token", e);
			throw new ForbiddenException("invalid ID token");
		}		

		// token is valid, we can trust that it came from the issuer
		
		return claims;
	}

	private void checkUserInfo(OidcConfig oidcConfig, String accessTokenString, String issuer, String clientId, String sub) {
		
		if (oidcConfig.getRequireUserinfoClaim().isEmpty()) {
			
			if (this.isDebug) {
				logger.info("no required userinfo claims");
			}
			return;
		}
			
		if (accessTokenString == null) {			
			throw new NotAuthorizedException("cannot check userinfo endpoint without an access token");
		}
				
		URI userInfoEndpoint = this.userInfoEndpointURIs.get(oidcConfig);
		BearerAccessToken token = new BearerAccessToken(accessTokenString);
		
		if (this.isDebug) {
			logger.info("get userinfo from " + userInfoEndpoint);
		}

		try {
			// Make the request
			HTTPResponse httpResponse = new UserInfoRequest(userInfoEndpoint, token)
			    .toHTTPRequest()
			    .send();

			// Parse the response
			UserInfoResponse userInfoResponse = UserInfoResponse.parse(httpResponse);
	
			if (! userInfoResponse.indicatesSuccess()) {
			    // The request failed, e.g. due to invalid or expired token
			    logger.error("userinfo request failed: " + 
			    		userInfoResponse.toErrorResponse().getErrorObject().getCode() + " " + 
			    		userInfoResponse.toErrorResponse().getErrorObject().getDescription());
			    
			    throw new InternalServerErrorException("userinfo request failed");
			}
	
			// sanity checks, compare most essential id_token and userinfo claims
			UserInfo userInfo = userInfoResponse.toSuccessResponse().getUserInfo();
			Map<String, Object> userinfoClaims = userInfo.toJWTClaimsSet().getClaims();
			
			if (this.isDebug) {
				logger.info("claims from userinfo endpoint: ");
				for (String k : userinfoClaims.keySet()) {
					logger.info("claim " + k + ": " + userinfoClaims.get(k));
				}
			}
			
			if (!userInfo.getSubject().getValue().equals(sub)) {
				throw new InternalServerErrorException("id_token and userinfo subjects differ");
			}
			
			if (!userInfo.getIssuer().getValue().equals(issuer)) {
				throw new InternalServerErrorException("id_token and userinfo issuer differ");
			}
			
			if (!userInfo.getAudience().get(0).getValue().equals(clientId)) {
				throw new InternalServerErrorException("id_token and userinfo clientId differ");
			}			
			
			if (!hasRequiredClaim(oidcConfig.getOidcName(), oidcConfig.getRequireUserinfoClaim(), userinfoClaims)) {
				if (this.isDebug) {
					logger.info("access denied. Required userinfo claim not found: " + oidcConfig.getRequireUserinfoClaim());
				}
				throw new ForbiddenException("access denied");
			}
			
		} catch (com.nimbusds.oauth2.sdk.ParseException | IOException e) {
			throw new InternalServerErrorException("oidc userinfo error", e);
		}
	}

	/**
	 * Get the oidc config
	 * 
	 * If the same issuer has multiple configs, iterate in priority order.
	 * 
	 * @param issuer
	 * @param claims
	 * @return
	 */
	protected OidcConfig getOidcConfig(String issuer,  String clientId, Map<String, Object> claims) {
		if (this.isDebug) {
			logger.info("searching oidc config");
		}
		for (OidcConfig oidc : sortedOidcConfigs) {
			if (this.isDebug) {
				logger.info("check if oidc config " + oidc.getOidcName() + " is suitable");
			}	
			if (!oidc.getIssuer().equals(issuer)) {
				if (this.isDebug) {
					logger.info("issuer '" + issuer + "' does not match " + oidc.getIssuer() + "'");
				}
				continue;
			}
			
			if (!oidc.getClientId().equals(clientId)) {
				if (this.isDebug) {
					logger.info("clientId '" + clientId  + "' does not match " + oidc.getClientId() + "'");
				}
				continue;
			}
			
			if (claims.get(oidc.getClaimUserId()) == null) {
				if (this.isDebug) {
					logger.info("claim '" + oidc.getClaimUserId()  + "' not found");
				}
				continue;
			}
			
			if (!hasRequiredClaim(oidc.getOidcName(), oidc.getRequireClaim(), claims)) {
				continue;
			}
					
			if (this.isDebug) {
				logger.info("oidc config found: " + oidc.getOidcName());
			}
			// this is fine
			return oidc;
		}
		if (this.isDebug) {
			logger.info("oidc config not found");
		}
		throw new ForbiddenException("oidc config not found for issuer " + issuer);
	}

	protected boolean hasRequiredClaim(String oidcName, String requiredClaim, Map<String, Object> claims) {
		if (!requiredClaim.isEmpty()) {
			String[] keyValue = requiredClaim.split("=");
			
			String key = keyValue[0];
			String value = null;
			
			if (keyValue.length == 2) {
				value = keyValue[1];
			}
			
			Object claimObj = claims.get(key);
			if (claimObj == null) {
				logger.info("oidc " + oidcName + " requires a non existent claim " + requiredClaim);					
				return false;
			}
			
			if (value != null) {
				String claimValue = claimObj.toString();
				
				if (!claimValue.equals(value)) {
					if (this.isDebug) {
						logger.info("claim " + key + " has value '" + claimValue  + "', which does not match expected '" + value + "'");
					}
					return false;
				}
			} else {
				if (isDebug) {
					logger.info("claim " + key + " found, value is not required");
				}
			}
			
			// a claims is required and it's found
			return true;
		}
		// no claim is required, anything goes
		return true;
	}

	/**
	 * Get claim value
	 * 
	 * Return null if the claimName is an empty string (default in Chipster config).
	 * 
	 * @param claimName
	 * @param claims
	 * @return
	 */
	private String getClaim(String claimName, IDTokenClaimsSet claims) {
		if (!claimName.isEmpty()) {
			return claims.getStringClaim(claimName);
		}
		return null;
	}

	@GET
	@Path("configs")
	@RolesAllowed(Role.UNAUTHENTICATED)
	@Produces(MediaType.APPLICATION_JSON)
	public ArrayList<OidcConfig> getOidcConfs() {
		return sortedOidcConfigs;
	}	
}

	