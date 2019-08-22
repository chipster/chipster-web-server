package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
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
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.utils.URIBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
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
	public static final String CONF_DEBUG = "auth-oidc-debug";
		
	private AuthTokens tokenTable;
	private UserTable userTable;

	ArrayList<OidcConfig> sortedOidcConfigs = new ArrayList<>();	
	HashMap<OidcConfig, IDTokenValidator> validators = new HashMap<>();
	ArrayList<OidcConfig> oidcConfigs = new ArrayList<>();

	private boolean isDebug;

	public OidcResource(AuthTokens tokenTable, UserTable userTable, Config config) throws URISyntaxException, IOException {
		this.tokenTable = tokenTable;
		this.userTable = userTable;
		this.isDebug = config.getBoolean(CONF_DEBUG);
				
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
			 
			// multiple oidcConfigs may have the same issuer
			oidcConfigs.add(oidc);
			
			// if multiple oidConfigs have the same issuer, thay must have the same clientId
			// because before validation we know only the issuer
			this.validators.put(oidc, getValidator(issuer, clientId));			
		}
		
		sortedOidcConfigs = new ArrayList<OidcConfig>(oidcConfigs);
		sortedOidcConfigs.sort((a, b) -> a.getPriority().compareTo(b.getPriority()));
		
		for (OidcConfig oidc : sortedOidcConfigs) {			
			logger.info("OpenID Connect issuer " + oidc.getIssuer() + " enabled");
		}
	}
	
	private IDTokenValidator getValidator(String issuer, String clientId) throws URISyntaxException, IOException {
		
		URI discoveryUri = new URIBuilder(issuer)
				.setPath(".well-known/openid-configuration")
				.build();
		String discoveryString = IOUtils.toString(discoveryUri, Charset.defaultCharset());
		@SuppressWarnings("unchecked")
		HashMap<String, Object> discoveryDoc = RestUtils.parseJson(HashMap.class, discoveryString);
		
		String jwkSetURL = (String) discoveryDoc.get("jwks_uri");
		
		if (jwkSetURL == null) {
			throw new IllegalStateException("OpenID Connect jwk_uri is null, cannot verify login tokens without it");
		} else {
			logger.info("download OpenID Connect keys from " + jwkSetURL);
		}

		// Create validator for signed ID tokens
		// it should download the token signing keys and keep them updated (e.g. daily for google) 
		return new IDTokenValidator(new Issuer(issuer), new ClientID(clientId), JWSAlgorithm.RS256, new URL(jwkSetURL));
	}

	@POST
	@RolesAllowed(Role.UNAUTHENTICATED)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.TEXT_PLAIN)
	@Transaction
	public Response createTokenFromOidc(HashMap<String, String> json, @Context SecurityContext sc) throws GeneralSecurityException, IOException, ParseException {

		String idTokenString = json.get("idToken");
		
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
		OidcConfig oidcConfig = getOidcConfig(issuer, clientId, idToken.getJWTClaimsSet());
		
		IDTokenValidator validator = validators.get(oidcConfig);
			
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
				
		String sub = claims.getSubject().getValue();	
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
	 * Get the oidc config
	 * 
	 * If the same issuer has multiple configs, iterate in priority order.
	 * 
	 * @param issuer
	 * @param claims
	 * @return
	 */
	private OidcConfig getOidcConfig(String issuer,  String clientId, JWTClaimsSet claims) {
		for (OidcConfig oidc : sortedOidcConfigs) {
			if (this.isDebug) {
				logger.info("searching oidc config");
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
			
			if (claims.getClaim(oidc.getClaimUserId()) == null) {
				if (this.isDebug) {
					logger.info("claim '" + oidc.getClaimUserId()  + "' not found");
				}
				continue;
			}
			
			if (!oidc.getRequireClaim().isEmpty()) {
				String[] keyValue = oidc.getRequireClaim().split("=");
				String key = keyValue[0];
				String value = keyValue[1];
				Object claimObj = claims.getClaim(key);
				if (claimObj == null) {
					logger.info("oidc " + oidc.getOidcName() + " requires a non existent claim " + oidc.getRequireClaim());					
					continue;
				}
				String claimValue = claimObj.toString();
				
				if (!claimValue.equals(value)) {
					if (this.isDebug) {
						logger.info("claim " + key + " has value '" + claimValue  + "', which does not match expected '" + value + "'");
					}
					continue;
				}
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
