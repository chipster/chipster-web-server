package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.Request;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest.Builder;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.auth.model.OidcLoginSession;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.auth.oidc.NimbusHelpers;
import fi.csc.chipster.auth.oidc.OidcConfig;
import fi.csc.chipster.auth.oidc.OidcProviders;
import fi.csc.chipster.auth.oidc.loginsessions.OidcLoginSessions;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.rest.websocket.PubSubConfigurator;
import fi.csc.chipster.rest.websocket.PubSubEndpoint;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Rest endpoint for logging in to Chipster with OpenID Connect
 * 
 * At the moment the OIDC client runs in a browser, so here we only receive the
 * id_token,
 * validate it and create a new Chipster token. Optionally also a OIDC userinfo
 * endpoint is
 * queried and its claim checked.
 * 
 * Running OIDC client in the browser allows the token to be stored directly in
 * the local storage of
 * the main Chipster app. If OIDC return URL pointed to the server, it would be
 * difficult
 * to transfer the token to the main app. Ideas:
 * - Save the to the local storage. Doesn't work if the main app (web-server)
 * and the OIDC authentication handler
 * (auth) are served from different domains.
 * - Respond with a html having iframe which is loaded from the web-server,
 * which stores the token to
 * the local storage. Worked in Chrome and Firefox, but not in Safari, if I
 * remember correctly. Safari
 * uses the page domain hierarchy to keep local storages separated.
 * - Pass the token in the query parameter of redirect URL. Should work, but
 * insecure?
 * 
 * @author klemela
 */
@Path("oidc")
public class OidcResource {

	private static final String KEY_CHIPSTER_OIDC_LOGIN_SESSION_ID = "chipsterOidcLoginSessionId";

	public static final String COMPARISON_STRING = "string";
	public static final String COMPARISON_JSON_ARRAY_ALL = "jsonArrayAll";
	public static final String COMPARISON_JSON_ARRAY_ANY = "jsonArrayAny";

	private static final Logger logger = LogManager.getLogger();

	public static final String CONF_DEBUG = "auth-oidc-debug";

	private AuthTokens authTokens;
	private UserTable userTable;

	private boolean isDebug;

	private OidcProviders oidcProviders;

	private OidcLoginSessions chipsterOidcLoginSessions;

	public OidcResource(OidcProviders oidcProviders, OidcLoginSessions chipsterOidcLoginSessions) {
		this.oidcProviders = oidcProviders;
		this.chipsterOidcLoginSessions = chipsterOidcLoginSessions;
	}

	public void init(AuthTokens authTokens, UserTable userTable, Config config) throws URISyntaxException, IOException {
		this.authTokens = authTokens;
		this.userTable = userTable;
		this.isDebug = config.getBoolean(CONF_DEBUG);
	}

	@GET
	@Path("configs")
	@RolesAllowed(Role.UNAUTHENTICATED)
	@Produces(MediaType.APPLICATION_JSON)
	public ArrayList<OidcConfig> getPublicOidcConfs() {

		return oidcProviders.getPublicOidcConfigs();
	}

	@POST
	@Path("login")
	@RolesAllowed({ Role.UNAUTHENTICATED })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public HashMap<String, Object> startAuthentication(@QueryParam("oidcName") String oidcName,
			@Context Request jerseyRequest) {

		logger.info("start oidc authentication " + oidcName);

		OidcConfig oidcConfig = oidcProviders.getOidcConfig(oidcName);

		String sourceIp = getSourceIp(oidcConfig, jerseyRequest);

		String authorizationEndpoint = oidcProviders.getAuthorizationEndpointURI(oidcName);

		// Generate random state string to securely pair the callback to this request
		State state = new State();

		// Generate nonce for the ID token
		Nonce nonce = new Nonce();

		// store state, nonce and oidcName for callback
		UUID chipsterOidcLoginSessionId = this.chipsterOidcLoginSessions.add(state.getValue(), nonce.getValue(),
				oidcName, sourceIp);

		Builder request = NimbusHelpers.createAuthentiationRequest(oidcConfig.getClientId(),
				oidcConfig.getRedirectPath(),
				state,
				nonce,
				oidcConfig.getResponseType(), oidcConfig.getScope(), authorizationEndpoint);

		addParameters(request, oidcConfig.getParameter());

		HashMap<String, Object> json = new HashMap<>();
		// The URI to send the user-user browser to the OpenID provider
		json.put("url", request.build().toURI());
		// chipster ID to find the state and nonce later
		json.put(KEY_CHIPSTER_OIDC_LOGIN_SESSION_ID, chipsterOidcLoginSessionId);

		if (this.isDebug) {
			logger.info("response: " + json);
		}

		return json;
	}

	private String getSourceIp(OidcConfig oidcConfig, Request jerseyRequest) {
		switch (oidcConfig.getIpLimit()) {
			case "none":
				return null;
			case "source":
				return jerseyRequest.getRemoteAddr();
			case "forwarded":
				return jerseyRequest.getHeader(RestUtils.X_FORWARDED_FOR);
			default:
				throw new InternalServerErrorException("unknown ip-limit: " + oidcConfig.getIpLimit());
		}
	}

	@POST
	@Path("callback")
	@RolesAllowed({ Role.UNAUTHENTICATED })
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.TEXT_PLAIN)
	@Transaction
	public Response oidcCallback(HashMap<String, String> requestJson, @Context Request jerseyRequest) {

		if (this.isDebug) {
			logger.info("oidc callback " + requestJson);
		} else {
			logger.info("oidc callback " + requestJson.get("oidcName"));
		}

		String code = requestJson.get("code");
		String stateFromRequest = requestJson.get("state");
		String oidcName = requestJson.get("oidcName");
		UUID chipsterOidcLoginSessionId = UUID.fromString(requestJson.get(KEY_CHIPSTER_OIDC_LOGIN_SESSION_ID));

		if (code == null) {
			throw new BadRequestException("no code");
		}

		if (stateFromRequest == null) {
			throw new BadRequestException("no state");
		}

		if (oidcName == null) {
			throw new BadRequestException("no oidcName");
		}

		if (chipsterOidcLoginSessionId == null) {
			throw new BadRequestException("no " + KEY_CHIPSTER_OIDC_LOGIN_SESSION_ID);
		}

		// find (and remove) the login session
		OidcLoginSession chipsterOidcLogin = this.chipsterOidcLoginSessions.remove(chipsterOidcLoginSessionId);

		if (chipsterOidcLogin == null) {
			throw new NotFoundException("login session not found, probably expired");
		}

		OidcConfig oidcConfig = oidcProviders.getOidcConfig(chipsterOidcLogin.getOidcName());

		if (chipsterOidcLogin.getSourceIp() == null) {
			logger.info("IP limit is disabled (or header was not found)");
		} else if (chipsterOidcLogin.getSourceIp().equals(getSourceIp(oidcConfig, jerseyRequest))) {
			logger.info("accepted source IP " + chipsterOidcLogin.getSourceIp());
		} else {
			throw new BadRequestException("network changed");
		}

		/*
		 * The callback uri is parsed already in the app. Then only thing left from this
		 * example is to check that the state from the request matches with the state on
		 * the server
		 * https://connect2id.com/products/nimbus-oauth-openid-connect-sdk/examples/
		 * openid-connect/oidc-auth
		 */
		if (!chipsterOidcLogin.getState().equals(stateFromRequest)) {
			throw new BadRequestException("state doesn't match");
		}

		URI tokenEndpoint = oidcProviders.getTokenEndpoint(chipsterOidcLogin.getOidcName());

		OIDCTokenResponse tokenResponse = NimbusHelpers.tokenRequest(oidcConfig, new AuthorizationCode(code),
				tokenEndpoint, oidcConfig.getScope());

		// Get the ID and access token, the server may also return a refresh token
		JWT idToken = tokenResponse.getOIDCTokens().getIDToken();
		AccessToken accessToken = tokenResponse.getOIDCTokens().getAccessToken();
		// RefreshToken refreshToken = tokenResponse.getOIDCTokens().getRefreshToken();

		String chipsterToken = createTokenFromOidc(oidcConfig, idToken, accessToken, chipsterOidcLogin.getNonce());

		return Response.ok(chipsterToken).build();
	}

	/**
	 * Validate id_token and create Chipster token
	 * 
	 * This part can be extensively tested, because here interaction with the OIDC
	 * authoriization/resource server has been done already.
	 * 
	 * @param oidcConfig
	 * @param idToken
	 * @param accessToken
	 * @param nonce
	 * @return
	 */
	public String createTokenFromOidc(OidcConfig oidcConfig, JWT idToken, AccessToken accessToken,
			String nonce) {

		if (idToken == null) {
			throw new ForbiddenException("no ID token");
		}

		IDTokenValidator validator = oidcProviders.getValidator(oidcConfig.getOidcName());

		/*
		 * The following call to validate() is all that is left from example
		 * https://connect2id.com/blog/how-to-validate-an-openid-connect-id-token ,
		 * when we use cached validators.
		 */

		// Set the expected nonce, leave null if none
		Nonce expectedNonce = new Nonce(nonce);

		JWTClaimsSet idTokenClaims;

		try {
			idTokenClaims = validator.validate(idToken, expectedNonce).toJWTClaimsSet();
		} catch (BadJOSEException e) {
			// Invalid signature or claims (iss, aud, exp...)
			throw new ForbiddenException("id_token validation failed", e);
		} catch (JOSEException e) {
			// Internal processing exception
			throw new InternalServerErrorException("id_token validation failed", e);
		} catch (ParseException e) {
			throw new InternalServerErrorException("failed to parse id_token claims", e);
		}

		// token is valid, we can trust that it came from the issuer

		if (this.isDebug) {
			logger.info("id_token claims after validation: ");
			printClaims(idTokenClaims.getClaims());
		}

		Map<String, Object> claims = null;

		if (oidcConfig.getQueryUserInfo()) {
			try {
				logger.info("get userInfo");
				JWTClaimsSet userInfoClaims = oidcProviders.getUserInfo(accessToken, oidcConfig.getOidcName())
						.toJWTClaimsSet();

				if (this.isDebug) {
					logger.info("claims from userinfo endpoint: ");
					printClaims(userInfoClaims.getClaims());
				}

				// sanity checks, compare the sub claim from id_token and userinfo claims
				if (!userInfoClaims.getSubject().equals(idTokenClaims.getSubject())) {
					throw new InternalServerErrorException("id_token and userinfo subjects differ");
				}

				// create a new map, because the map from getClaims() does not allow
				// modifications
				claims = new HashMap<>(userInfoClaims.getClaims());
				// merge claims
				// use the value from id_token if the same claim is in both
				claims.putAll(idTokenClaims.getClaims());

			} catch (ParseException e) {
				throw new InternalServerErrorException("failed to parse userInfo claims", e);
			}
		} else {
			claims = idTokenClaims.getClaims();
		}

		checkClaims(oidcConfig, claims);

		String chipsterToken = getChipsterToken(claims, oidcConfig);

		logger.info("login successful");

		return chipsterToken;
	}

	private String getChipsterToken(Map<String, Object> claims, OidcConfig oidcConfig) {

		String name = getStringOrNull("name", claims);
		String email = getStringOrNull("email", claims);
		Boolean emailVerified = Boolean.parseBoolean(getStringOrNull("email_verified", claims));

		// use different auth names in Chipster based on the claims that we get
		// claim "sub" is used by default
		String username = getStringOrNull(oidcConfig.getClaimUserId(), claims);

		String userIdPrefix = oidcConfig.getUserIdPrefix();

		if (username == null) {
			throw new ForbiddenException("username not found from claim " + oidcConfig.getClaimUserId());
		}

		UserId userId = new UserId(userIdPrefix, username);

		// store only verified emails
		if (oidcConfig.getVerifiedEmailOnly() && (emailVerified == null || emailVerified == false)) {
			email = null;
		}

		String organization = getStringOrNull(oidcConfig.getClaimOrganization(), claims);

		userTable.addOrUpdateFromLogin(userId, email, organization, name);

		HashSet<String> roles = Stream.of(Role.CLIENT, Role.OIDC).collect(Collectors.toCollection(HashSet::new));
		String token = authTokens.createNewUserToken(userId.toUserIdString(), roles, name);

		return token;
	}

	private void checkClaims(OidcConfig oidcConfig, Map<String, Object> claims) {

		if (!hasRequiredClaim(
				oidcConfig.getOidcName(),
				oidcConfig.getRequiredClaimKey(),
				oidcConfig.getRequiredClaimValue(),
				oidcConfig.getRequiredClaimValueComparison(),
				claims)) {
			if (this.isDebug) {
				logger.info("access denied. Required userinfo claim not found: "
						+ oidcConfig.getRequiredClaimKey());
			}
			throw new ForbiddenException("no required claim or value");
		}
	}

	protected boolean hasRequiredClaim(String oidcName, String requiredClaimKey, String requiredClaimValue,
			String comparison, Map<String, Object> claims) {
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
							logger.info("claim " + requiredClaimKey + " has value '" + claimValue
									+ "', which does not match expected '" + requiredClaimValue + "'");
						}
						return false;
					}
				} else if (COMPARISON_JSON_ARRAY_ANY.equals(comparison)
						|| COMPARISON_JSON_ARRAY_ALL.equals(comparison)) {

					try {
						@SuppressWarnings("unchecked")
						HashSet<String> requiredValues = RestUtils.parseJson(HashSet.class, requiredClaimValue);
						@SuppressWarnings("unchecked")
						HashSet<String> usersValues = RestUtils.parseJson(HashSet.class, claimValue);

						if (COMPARISON_JSON_ARRAY_ANY.equals(comparison)) {

							if (!requiredValues.stream().anyMatch(usersValues::contains)) {
								if (this.isDebug) {
									logger.info("claim " + requiredClaimKey + " has value '" + claimValue
											+ "', which does not contain any of expected '" + requiredClaimValue + "'");
								}
								return false;
							}

						} else if (COMPARISON_JSON_ARRAY_ALL.equals(comparison)) {
							if (!requiredValues.stream().allMatch(usersValues::contains)) {
								if (this.isDebug) {
									logger.info("claim " + requiredClaimKey + " has value '" + claimValue
											+ "', which does not contain all of expected '" + requiredClaimValue + "'");
								}
								return false;
							}
						} else {
							logger.error("impossible");
							return false;
						}

					} catch (InternalServerErrorException e) {
						logger.error("failed to parse required claim value as json. configured: " + requiredClaimValue
								+ ", from oidc: " + claimValue);
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
	private String getStringOrNull(String claimName, Map<String, Object> claims) {
		if (!claimName.isEmpty()) {
			Object value = claims.get(claimName);
			if (value == null) {
				return null;
			}
			if (value instanceof String) {
				return (String) value;
			}
			throw new InternalServerErrorException("claim " + claimName + " value is not String: " + value);
		}
		return null;
	}

	private void printClaims(Map<String, Object> claims) {

		for (String k : claims.keySet()) {
			logger.info("claim " + k + ": " + claims.get(k));
		}
	}

	private void addParameters(Builder request, String parameter) {
		if (parameter != null) {
			// public client in app did support multiple parameters like this, but this is
			// not documented in chipster-defaults.yaml
			for (String entry : Arrays.asList(parameter.split(" "))) {
				String[] parts = entry.split("=");
				if (parts.length != 2) {
					throw new InternalServerErrorException("cannot parse " + parameter);
				}
				String key = parts[0];
				String value = parts[1];
				logger.info("add customer parameter " + key + "=" + value);
				request.customParameter(key, value);
			}
		}
	}
}
