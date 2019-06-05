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
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
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
	public static final String CONF_TEXT = "auth-oidc-text";
	public static final String CONF_PRIORITY = "auth-oidc-priority";
	public static final String CONF_VERIFIED_EMAIL_ONLY = "auth-oidc-verified-email-only";
	public static final String CONF_CLAIM_ORGANIZATION = "auth-oidc-claim-organization";
	public static final String CONF_CLAIM_PRIMARY_USER_ID = "auth-oidc-claim-primary-user-id";
	public static final String CONF_CLAIM_SECONDARY_USER_ID = "auth-oidc-claim-secondary-user-id";
	public static final String CONF_SECONDARY_AUTH = "auth-oidc-secondary-auth";
		
	private TokenTable tokenTable;
	private UserTable userTable;

	ArrayList<OidcConfig> sortedOidcConfigs = new ArrayList<>();	
	HashMap<String, IDTokenValidator> validators = new HashMap<>();
	HashMap<String, OidcConfig> oidcConfigs = new HashMap<>();

	public OidcResource(TokenTable tokenTable, UserTable userTable, Config config) throws URISyntaxException, IOException {
		this.tokenTable = tokenTable;
		this.userTable = userTable;
				
		for (String oidcName : config.getConfigEntries(OidcResource.CONF_ISSUER + "-").keySet()) {
			String issuer = config.getString(CONF_ISSUER, oidcName);
			String clientId = config.getString(CONF_CLIENT_ID, oidcName);

			OidcConfig oidc = new OidcConfig();
			
			oidc.setIssuer(issuer);
			oidc.setClientId(clientId);
			oidc.setRedirectPath(config.getString(CONF_REDIRECT_URI, oidcName));
			oidc.setResponseType(config.getString(CONF_RESPONSE_TYPE, oidcName));
			oidc.setLogo(config.getString(CONF_LOGO, oidcName));
			oidc.setText(config.getString(CONF_TEXT, oidcName));
			oidc.setPriority(Integer.parseInt(config.getString(CONF_PRIORITY, oidcName)));
			oidc.setVerifiedEmailOnly(config.getBoolean(CONF_VERIFIED_EMAIL_ONLY, oidcName));
			oidc.setOidcName(oidcName);
			oidc.setClaimOrganization(config.getString(CONF_CLAIM_ORGANIZATION, oidcName));
			oidc.setClaimPrimaryUserId(config.getString(CONF_CLAIM_PRIMARY_USER_ID, oidcName));
			oidc.setClaimSecondaryUserId(config.getString(CONF_CLAIM_SECONDARY_USER_ID, oidcName));
			oidc.setSecondaryAuth(config.getString(CONF_SECONDARY_AUTH, oidcName));
			
			oidcConfigs.put(issuer, oidc);
			
			this.validators.put(issuer, getValidator(issuer, clientId));			
		}
		
		sortedOidcConfigs = new ArrayList<OidcConfig>(oidcConfigs.values());
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
	@Produces(MediaType.APPLICATION_JSON)
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

		// what token says about its issuer?
		String issuer = idToken.getJWTClaimsSet().getIssuer();
		
		// get the validator of that issuer
		IDTokenValidator validator = validators.get(issuer);
			
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
		
		for (String claim : idToken.getJWTClaimsSet().getClaims().keySet()) {
			System.out.println(claim + ": " + claims.getStringClaim(claim));
		}
							
		OidcConfig oidcConfig = oidcConfigs.get(issuer);
		
		// use different auth names in Chipster based on the claims that we get
		String primaryUsername = getClaim(oidcConfig.getClaimPrimaryUserId(), claims);
		String secondaryUsername = getClaim(oidcConfig.getClaimSecondaryUserId(), claims);
		String primaryAuth = oidcConfig.getOidcName();
		String secondaryAuth = oidcConfig.getSecondaryAuth();
		
		UserId userId = null;
		if (oidcConfig.getClaimPrimaryUserId().equals("sub")) {
			userId = new UserId(primaryAuth, sub);
			
		} else if (primaryUsername != null) {
			userId = new UserId(primaryAuth, primaryUsername);
			
		} else if (secondaryUsername != null) {
			userId = new UserId(secondaryAuth, secondaryUsername);
			
		} else {
			throw new ForbiddenException("username not found from claims" + oidcConfig.getClaimPrimaryUserId() + " or " + oidcConfig.getClaimSecondaryUserId());
		}
		
		// store only verified emails
		if (oidcConfig.getVerifiedEmailOnly() && (emailVerified == null || emailVerified == false)) {
			email = null;
		}
		
		String organization = getClaim(oidcConfig.getClaimOrganization(), claims);				
		User user = new User(userId.getAuth(), userId.getUsername(), email, organization, name);
				
		userTable.addOrUpdate(user);

		HashSet<String> roles = Stream.of(Role.CLIENT, Role.OIDC).collect(Collectors.toCollection(HashSet::new));
		Token token = tokenTable.createAndSaveToken(userId.toUserIdString(), roles);
		// name for the navbar
		token.setName(user.getName());

		return Response.ok(token).build();	
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
