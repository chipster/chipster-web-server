package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.Transaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Rest endpoint for logging in to Chipster with OpenID Connect
 * 
 * At the moment the OIDC client runs in a browser, so here we only receive the id_token, 
 * validate it and create a new Chipster token. Optionally also a OIDC userinfo endpoint is 
 * queried and its claim checked.  
 * 
 * Running OIDC client in the browser allows the token to be stored directly in the local storage of 
 * the main Chipster app. If OIDC return URL pointed to the server, it would be difficult 
 * to transfer the token to the main app. Ideas:
 * - Save the to the local storage. Doesn't work if the main app (web-server) and the OIDC authentication handler 
 * (auth) are served from different domains.
 * - Respond with a html having iframe which is loaded from the web-server, which stores the token to 
 * the local storage. Worked in Chrome and Firefox, but not in Safari, if I remember correctly. Safari 
 * uses the page domain hierarchy to keep local storages separated.
 * - Pass the token in the query parameter of redirect URL. Should work, but insecure? 
 * 
 * @author klemela
 */
@Path("oidc")
public class OidcResource {

	public static final String COMPARISON_STRING = "string";
	public static final String COMPARISON_JSON_ARRAY_ALL = "jsonArrayAll";
	public static final String COMPARISON_JSON_ARRAY_ANY = "jsonArrayAny";

	private static final Logger logger = LogManager.getLogger();
	
	public static final String CONF_DEBUG = "auth-oidc-debug";
		
	private AuthTokens authTokens;
	private UserTable userTable;

	private boolean isDebug;

	private OidcProviders oidcProviders;

	public OidcResource(OidcProviders oidcProviders) {
		this.oidcProviders = oidcProviders;
	}


	public void init(AuthTokens authTokens, UserTable userTable, Config config) throws URISyntaxException, IOException {
		this.authTokens = authTokens;
		this.userTable = userTable;
		this.isDebug = config.getBoolean(CONF_DEBUG);
	}		
	
	@POST
	@RolesAllowed(Role.UNAUTHENTICATED)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.TEXT_PLAIN)
	@Transaction
	public Response createTokenFromOidc(HashMap<String, String> json) throws GeneralSecurityException, IOException, ParseException {

		String idTokenString = json.get("idToken");
		String accessTokenString = json.get("accessToken");
		
		String chipsterToken = createTokenFromOidc(idTokenString, accessTokenString);
		
		return Response.ok(chipsterToken).build();
	}
		
	public String createTokenFromOidc(String idTokenString, String accessTokenString) throws GeneralSecurityException, IOException, ParseException {
		
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
		
		IDTokenValidator validator = oidcProviders.getValidator(oidcConfig);
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
				
		userTable.addOrUpdateFromLogin(userId, email, organization, name);

		HashSet<String> roles = Stream.of(Role.CLIENT, Role.OIDC).collect(Collectors.toCollection(HashSet::new));
		String token = authTokens.createNewUserToken(userId.toUserIdString(), roles, name);

		return token;	
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
		
		if (oidcConfig.getRequiredUserinfoClaimKey().isEmpty()) {
			
			if (this.isDebug) {
				logger.info("no required userinfo claims");
			}
			return;
		}
			
		if (accessTokenString == null) {			
			throw new NotAuthorizedException("cannot check userinfo endpoint without an access token");
		}
				
		
		UserInfo userInfo = oidcProviders.getUserInfo(oidcConfig, accessTokenString, isDebug);

		Map<String, Object> userinfoClaims;
		try {
			userinfoClaims = userInfo.toJWTClaimsSet().getClaims();
		} catch (com.nimbusds.oauth2.sdk.ParseException e) {
			throw new InternalServerErrorException("parsing userinfo failed", e);
		}
		
		if (this.isDebug) {
			logger.info("claims from userinfo endpoint: ");
			for (String k : userinfoClaims.keySet()) {
				logger.info("claim " + k + ": " + userinfoClaims.get(k));
			}
		}
		
		// sanity checks, compare the sub claim from id_token and userinfo claims
		
		if (!userInfo.getSubject().getValue().equals(sub)) {
			throw new InternalServerErrorException("id_token and userinfo subjects differ");
		}
		
		if (!hasRequiredClaim(
				oidcConfig.getOidcName(), 
				oidcConfig.getRequiredUserinfoClaimKey(), 
				oidcConfig.getRequiredUserinfoClaimValue(), 
				oidcConfig.getRequiredUserinfoClaimValueComparison(), 
				userinfoClaims)) {
			if (this.isDebug) {
				logger.info("access denied. Required userinfo claim not found: " + oidcConfig.getRequiredUserinfoClaimKey());
			}
			throw new ForbiddenException(oidcConfig.getRequiredUserinfoClaimError());
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
		for (OidcConfig oidc : oidcProviders.getOidcConfigs()) {
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
			
			if (!hasRequiredClaim(
					oidc.getOidcName(), 
					oidc.getRequiredClaimKey(), 
					oidc.getRequiredClaimValue(), 
					oidc.getRequiredClaimValueComparison(), 
					claims)) {
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

	protected boolean hasRequiredClaim(String oidcName, String requiredClaimKey, String requiredClaimValue, String comparison, Map<String, Object> claims) {
		if (!requiredClaimKey.isEmpty()) {
			
			Object claimObj = claims.get(requiredClaimKey);
			if (claimObj == null) {
				logger.info("oidc " + oidcName + " requires a non existent claim " + requiredClaimKey);					
				return false;
			}
			
			if (!requiredClaimValue.isEmpty()) {
				String claimValue = claimObj.toString();
				
				if (COMPARISON_STRING.equals(comparison)) {
					if (!claimValue.equals(requiredClaimValue)) {
						if (this.isDebug) {
							logger.info("claim " + requiredClaimKey + " has value '" + claimValue  + "', which does not match expected '" + requiredClaimValue + "'");
						}
						return false;
					}
				} else if (COMPARISON_JSON_ARRAY_ANY.equals(comparison) || COMPARISON_JSON_ARRAY_ALL.equals(comparison)) {
				
					try {
						@SuppressWarnings("unchecked")
						HashSet<String> requiredValues = RestUtils.parseJson(HashSet.class, requiredClaimValue);
						@SuppressWarnings("unchecked")
						HashSet<String> usersValues = RestUtils.parseJson(HashSet.class, claimValue);
						
						if (COMPARISON_JSON_ARRAY_ANY.equals(comparison)) {
							
							if (!requiredValues.stream().anyMatch(usersValues::contains)) {
								if (this.isDebug) {
									logger.info("claim " + requiredClaimKey + " has value '" + claimValue  + "', which does not contain any of expected '" + requiredClaimValue + "'");
								}
								return false;
							}	
							
						} else if (COMPARISON_JSON_ARRAY_ALL.equals(comparison)) {
							if (!requiredValues.stream().allMatch(usersValues::contains)) {
								if (this.isDebug) {
									logger.info("claim " + requiredClaimKey + " has value '" + claimValue  + "', which does not contain all of expected '" + requiredClaimValue + "'");
								}
								return false;
							}
						} else {
							logger.error("impossible");
							return false;
						}
						
					} catch (InternalServerErrorException e) {
						logger.error("failed to parse required claim value as json. configured: " + requiredClaimValue + ", from oidc: " + claimValue);
						return false;
					}
					
				} else {
					logger.error("oidc " + oidcName + ", unknown required claim comparison: " + comparison);
					return false;
				}
			} else {
				if (isDebug) {
					logger.info("claim " + requiredClaimKey + " found, value is not required");
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
		return oidcProviders.getOidcConfigs();
	}	
}

	