package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.NewCookie.SameSite;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/**
 * Rest endpoint for logging in to Chipster with OpenID Connect
 * 
 * This describes mostly the overall process and role of the client app in this
 * process. See the comments of individual methods below for details of the
 * server implementation.
 * 
 * Chipster authentication process:
 * - App makes a request to /configs and receives the public information of
 * the login methods. App presents these options for user, who
 * selects one of them.
 * - App makes a request to server path /loginSession (in auth domain). The
 * selected login method is indicated with a queryParameter "oidcName". Chipster
 * server responds with a loginSessionId.
 * - The app saves the given loginSessionId to the browsers' localStorage and
 * sends user's browser to /login with the loginSessionId in the form post. The
 * server redirects the browser to the Authorization Server. User gives a
 * username and password to the Authorization Server, which redirects user's
 * browser to the Chipster's callback URL /callback with a "code" and "state" in
 * the query parameters.
 * - Chipster server checks that the "state" matches with the one in the login
 * session, and then saves the "code" in the login session. The server redirects
 * the browser back to the app path /oidc/callback in web-server domain.
 * - The app finds the loginSessionId and returnPath from the local storage and
 * makes a normal Ajax request to /loginSessionComplete with the loginSessionId
 * in the request body. The server responds with a Chipster token. The app save
 * the token in the local storage and navigates to the returnPath.
 * 
 * The main difficulty in implementation of OIDC in Chipster is that the app and
 * the auth are served from different domains. To make it safer to communicate
 * between these domains, we keep track of the process in a login session. This
 * allows us to track that the required requests are made in correct order and
 * each request is made only once. Additionally, it gives us a safe place to
 * store state, nonce and code. Login sessions can be stored either in memory
 * (prevents replication), or in database (which we have anyway).
 * 
 * Here are some alternatives that were considered for tranferring information
 * (like the Chipster token) between auth and the app:
 * 1. Save the token the to the local storage in the HTML and JavaScript
 * returned from the auth. This doesn't work because the main app (web-server)
 * and the OIDC authentication handler (auth) are served from different domains.
 * 2. Respond with a html having iframe which is loaded from the web-server,
 * which stores the token to the local storage. This worked in Chrome and
 * Firefox, but not in Safari. Safari uses the page domain hierarchy to keep
 * local storages separated.
 * 3. Pass the token in the query parameter of redirect URL. Should work, but
 * it's an insecure place (too visible) for the long-lived Chipster token.
 * 4. Have a specific sub-path (like "/oidc") in web-server to work as a reverse
 * proxy to the auth. This works, but it is difficult to follow and makes it
 * more difficult to serve the app from other web server or through a reverse
 * proxy. Most importantly, this doesn't work in the Angular development server.
 * 
 * @author klemela
 */
@Path("oidc")
public class OidcResource {

	private static final String KEY_LOGIN_SESSION_ID = "chipsterOidcLoginSessionId";

	/*
	 * Set __Host- prefix to make sure the cookie is not sent to other domains
	 * 
	 * This is also the default, when no domain is set, but this prefix makes it
	 * explicit.
	 * 
	 * As a side effect, we have to set path to "/" (send to all paths under auth),
	 * but the explicit domain protection is more important.
	 */
	private static final String COOKIE_LOGIN_SESSION_ID = "__Host-" + KEY_LOGIN_SESSION_ID;

	public static final String COMPARISON_STRING = "string";
	public static final String COMPARISON_JSON_ARRAY_ALL = "jsonArrayAll";
	public static final String COMPARISON_JSON_ARRAY_ANY = "jsonArrayAny";

	public static final String CONF_DEBUG = "auth-oidc-debug";
	public static final String CONF_MAX_CODE_SIZE = "auth-oidc-max-code-size";
	public static final String CONF_MAX_LOGIN_DURATION = "auth-oidc-max-login-duration";

	private static final Logger logger = LogManager.getLogger();

	private AuthTokens authTokens;
	private UserTable userTable;

	private boolean isDebug;

	private OidcProviders oidcProviders;

	private OidcLoginSessions loginSessions;

	private ServiceLocatorClient serviceLocator;

	private String webServerUri;

	private int maxCodeSize;

	private int maxLoginDuration;

	public OidcResource(OidcProviders oidcProviders, OidcLoginSessions loginSessions,
			ServiceLocatorClient serviceLocator) {
		this.oidcProviders = oidcProviders;
		this.loginSessions = loginSessions;
		this.serviceLocator = serviceLocator;
	}

	public void init(AuthTokens authTokens, UserTable userTable, Config config) throws URISyntaxException, IOException {
		this.authTokens = authTokens;
		this.userTable = userTable;
		this.isDebug = config.getBoolean(CONF_DEBUG);
		this.maxCodeSize = config.getInt(CONF_MAX_CODE_SIZE);
		this.maxLoginDuration = config.getInt(CONF_MAX_LOGIN_DURATION);
	}

	/**
	 * Get public information about configured login methods
	 * 
	 * The app shows these to the user, who can select one of them to log in.
	 * 
	 * @return List of public information about configured login methods
	 */
	@GET
	@Path("configs")
	@RolesAllowed(Role.UNAUTHENTICATED)
	@Produces(MediaType.APPLICATION_JSON)
	public Response getPublicOidcConfs() {

		return Response.ok(oidcProviders.getPublicOidcConfigs()).build();
	}

	/**
	 * 1st request: Create a new OIDC login session
	 * 
	 * App makes a normal AJAX request in JavaScript from the web-server domain and
	 * gets back a loginSessionId.
	 * 
	 * @param oidcName      Which autentication configuration to use
	 * @param jerseyRequest To get users's source IP address
	 * @return json containing the loginSessionId
	 */
	@POST
	@Path("loginSession")
	@RolesAllowed({ Role.UNAUTHENTICATED })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response createLoginSession(@QueryParam("oidcName") String oidcName,
			@Context Request jerseyRequest) {

		// chipster ID to find the login session later
		UUID loginSessionId = RestUtils.createUUID();

		logger.info("(1/8) OIDC login " + shorten(loginSessionId) + ": login requested for " + oidcName);

		OidcConfig oidcConfig = oidcProviders.getOidcConfig(oidcName);

		String sourceIp = getSourceIp(oidcConfig, jerseyRequest);

		// save session
		this.loginSessions.add(loginSessionId, oidcName, sourceIp);
		HashMap<String, String> json = new HashMap<>() {
			{
				put(KEY_LOGIN_SESSION_ID, loginSessionId.toString());
			}
		};

		logger.info("(2/8) OIDC login " + shorten(loginSessionId) + ": session created from IP address "
				+ sourceIp);

		if (this.isDebug) {
			logger.info("response: " + json);
		}

		// return loginSesisonId to the app
		return Response
				.ok(json)
				.build();
	}

	/**
	 * 2nd request: Redirect to Authorization Server
	 *
	 * App navigates the browser to this address. auth component is served from
	 * different domain than the app, so the 1st request/endpoint cannot simply pass
	 * the chipsterLoginId in a cookie. The simplest way to pass the ID when
	 * navigating between domains would be a query parameter, but that would make it
	 * visible in page history and logs. The app hides it little bit better by
	 * sending it as a form post, in a request body.
	 * 
	 * The login continues only if
	 * - the session is found for the given loginSessionId and it hasn't passed this
	 * step already
	 * - the source IP address of this request matches with the one in the session
	 * 
	 * The browser is then redirected to the Authorization Server.
	 * 
	 * @param jerseyRequest For getting the source IP address from Jersey
	 * @return
	 */
	@POST
	@Path("login")
	@RolesAllowed({ Role.UNAUTHENTICATED })
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Transaction
	public Response authenticationRequest(
			@FormParam(KEY_LOGIN_SESSION_ID) String loginSessionIdString,
			@Context Request jerseyRequest) {

		if (loginSessionIdString == null) {
			throw new BadRequestException("no " + KEY_LOGIN_SESSION_ID);
		}

		UUID loginSessionId = UUID.fromString(loginSessionIdString);

		logger.info("(3/8) OIDC login " + shorten(loginSessionId)
				+ ": redirect requested");

		OidcLoginSession loginSession = this.loginSessions.getAndCheckExistence(loginSessionId);

		OidcConfig oidcConfig = oidcProviders.getOidcConfig(loginSession.getOidcName());

		checkSourceIp(loginSession, oidcConfig, jerseyRequest);

		/*
		 * State and nonce
		 * 
		 * State and nonce protect against attacks, where the attacker tries to give
		 * a forged code (prevented by state) or id_token (prevented by nonce) to the
		 * app.
		 * 
		 * OIDC spec suggests storing a secret value in a cookie or local storage
		 * https://openid.net/specs/openid-connect-core-1_0.html#NonceNotes. We can't
		 * use a cookie (safely), because now we are in the auth domain and the id_token
		 * is validated in the 4th request in the app domain. We'll save these in the
		 * loginSession instead, which is even safer. After this endpoint
		 * has responded with the state in the query parameter, this endpoint cannot be
		 * reused in the same login session and other endpoints don't reveal it.
		 * 
		 * OIDC spec also suggests using a hash of the session cookie as a state. That
		 * would allow this part to be stateless. However, because we use a login
		 * sessions to verify safe login process between domains, we can use it also to
		 * bind the login session, state and nonce together.
		 */

		/*
		 * Generate random state to protect callback endpoint against CSRF
		 * 
		 * We will keep on copy of it in the login session and it will be sent to the
		 * Authorization Server as a query parameter. Authorization Server will return
		 * it back to our callback endpoint.
		 * 
		 * Malicious website can wait for user to initiate our login process, and then
		 * redirect the browser tab of the malicious website to our callback endpoint
		 * with attackers code. Browser will send the loginSessionId cookie (because
		 * SameSite.LAX is needed), but the attacker doesn't know the state, so the
		 * callback request will be rejected.
		 */
		State state = new State();

		/*
		 * Nonce prevents id_token replay attacks. The basic form of that attack is when
		 * an attacker would create their own id_token, and then convinces the victim
		 * (with a link or malicious website) to login using that token. After that, the
		 * victim could be tricked to upload data to the attacker's account.
		 * 
		 * The risk that somebody could offer a wrong id_token for Chipster is very low,
		 * because we get it directly from the Authorization Server (maybe it would be
		 * possible with MITM between Chipster and Authorization Server?). Let's add a
		 * nonce anyway, just as a sanity check.
		 */
		Nonce nonce = new Nonce();

		// store state and nonce to the login session (which also ensures that this step
		// cannot be done twice for the same login session)
		loginSessions.validateAndUpdate(loginSession, state.getValue(), nonce.getValue());

		String redirectPath = getCallbackPath(oidcConfig, this.serviceLocator);

		String authorizationEndpoint = oidcProviders
				.getAuthorizationEndpointURI(loginSession.getOidcName());

		Builder request = NimbusHelpers.createAuthentiationRequest(oidcConfig.getClientId(),
				redirectPath,
				state,
				nonce,
				oidcConfig.getResponseType(), getScopeArray(oidcConfig), authorizationEndpoint);

		addParameters(request, oidcConfig.getParameter());

		URI redirectAddress = request.build().toURI();

		logger.info("(4/8) OIDC login " + shorten(loginSessionId)
				+ ": redirect browser to Authorization Server");

		if (this.isDebug) {
			// the redirectAddress has the state, so show it only in debug mode
			logger.info("redirect browser to " + redirectAddress);
		}

		// save loginSessionId in a cookie, where we can find it when we return
		// from the Authorization Server
		NewCookie loginSessionCookie = new NewCookie.Builder(COOKIE_LOGIN_SESSION_ID)
				.value(loginSessionId.toString())
				.httpOnly(true) // no access from JavaScript
				.sameSite(SameSite.LAX) // allow in top-level navigation (but not in fetch() or iframe)
				.secure(true) // allow access only on TLS or localhost
				.path("/") // required for __Host- cookie
				.maxAge(maxLoginDuration)
				.build();

		return Response.seeOther(redirectAddress)
				.cookie(loginSessionCookie)
				.build();
	}

	/**
	 * 3rd request: Return from the Authorization Server
	 * 
	 * After authentication, the Authorization Server redirects the user's browser
	 * here.
	 * 
	 * - Find the loginSessionId from the cookie (saved by the 2nd request)
	 * - Find the login session using the loginSessionId
	 * - Check that source IP and state match with the login session
	 * - Redirect the browser back to the app in web-server domain
	 * 
	 * @param jerseyRequest For getting the source IP address from Jersey
	 * @return
	 */
	@GET
	@Path("callback")
	@RolesAllowed({ Role.UNAUTHENTICATED })
	@Transaction
	public Response authenticationResponse(
			@CookieParam(COOKIE_LOGIN_SESSION_ID) Cookie loginSessionIdCookie,
			@Context Request jerseyRequest, @Context UriInfo uriInfo) {

		if (loginSessionIdCookie == null) {
			throw new BadRequestException("no " + KEY_LOGIN_SESSION_ID);
		}

		String loginSessionIdString = loginSessionIdCookie.getValue();

		if (loginSessionIdString == null) {
			throw new BadRequestException(KEY_LOGIN_SESSION_ID + " is null");
		}

		UUID loginSessionId = UUID.fromString(loginSessionIdString);

		logger.info("(5/8) OIDC login " + shorten(loginSessionId)
				+ ": returned from Authorization Server");

		OidcLoginSession loginSession = this.loginSessions.getAndCheckExistence(loginSessionId);

		OidcConfig oidcConfig = oidcProviders.getOidcConfig(loginSession.getOidcName());

		checkSourceIp(loginSession, oidcConfig, jerseyRequest);

		String requestUri = uriInfo.getRequestUri().toASCIIString();
		if (this.isDebug) {
			logger.info("oidc callback " + requestUri + ", session: " + RestUtils.asJson(loginSession));
		}

		AuthorizationCode code = NimbusHelpers.parseResponse(URI.create(requestUri), loginSession.getState());

		if (code == null) {
			throw new BadRequestException("no code");
		}

		/*
		 * Code should come as a redirect from the Authorization Server, but these are
		 * unauthenticated endpoints, where anybody can start new authentiation
		 * sessions and send anything in the code. Set a explicit limit for the code
		 * size to limit how large the DB can grow until those sessions are cleaned.
		 * 
		 * In practice, Grizzly probably also has a 8kB limit for the URI size.
		 */
		if (code.getValue().length() > this.maxCodeSize) {
			throw new BadRequestException("code is too long");
		}

		/*
		 * Save the code in the login session
		 * 
		 * This minimises it's visibility to the browser and JS.
		 * 
		 * We could already exchange the code for a idToken and accessToken here, but
		 * it's easier to save just one code. If the login process is interrupted,
		 * it will removed together with the login session soon.
		 */
		loginSessions.validateAndUpdate(loginSession, code.getValue());

		String appCallback = serviceLocator.getPublicUri(Role.WEB_SERVER) + "/oidc/callback";

		logger.info("(6/8) OIDC login " + shorten(loginSessionId)
				+ ": redirect browser back to app " + appCallback);

		NewCookie removeLoginSessionIdCookie = new NewCookie.Builder(COOKIE_LOGIN_SESSION_ID)
				.maxAge(0)
				.build();

		return Response.seeOther(URI.create(appCallback))
				.cookie(removeLoginSessionIdCookie)
				.build();
	}

	/**
	 * 4th request: Exchange code for a Chipster token
	 * 
	 * App makes again a normal AJAX request in JavaScript from the web-server
	 * domain.
	 * 
	 * - Use the code to make a reqeust to Authorization Server to get an id_token
	 * and access token
	 * - Call method createTokenFromOidc()
	 * 
	 * We get the code from the login session. The login is allowed only if the
	 * caller
	 * - Sends a valid loginSessionId
	 * - login session hasn't been removed already
	 * - source IP address of the caller matches with the one in the session
	 * - login session has code in it
	 * - Authentication server accepts the code
	 * 
	 * This could also be a DELETE request to /loginSession, but Jersey responded
	 * with 400 when sending a body in DELETE request (although it's used, and
	 * apparently works in SessionDbAdminResource).
	 * 
	 * @param codeCookie
	 * @param jerseyRequest To get users's source IP address from Jersey
	 * @return
	 */
	@POST
	@Path("loginSessionComplete")
	@RolesAllowed({ Role.UNAUTHENTICATED })
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.TEXT_PLAIN)
	@Transaction
	public Response oidcCallback(HashMap<String, Object> requestJson,
			@Context Request jerseyRequest) {

		if (this.isDebug) {
			logger.info("delete login session");
		}

		String loginSessionIdString = (String) requestJson.get(KEY_LOGIN_SESSION_ID);

		if (loginSessionIdString == null) {
			throw new BadRequestException("no " + KEY_LOGIN_SESSION_ID);
		}

		UUID loginSessionId = UUID.fromString(loginSessionIdString);

		logger.info("(7/8) OIDC login " + shorten(loginSessionId)
				+ ": delete login session");

		// get the session first to check the source IP before removing the session
		OidcLoginSession loginSession = this.loginSessions.getAndCheckExistence(loginSessionId);

		if (this.isDebug) {
			logger.info("login session: " + RestUtils.asJson(loginSession));
		}

		OidcConfig oidcConfig = oidcProviders.getOidcConfig(loginSession.getOidcName());

		checkSourceIp(loginSession, oidcConfig, jerseyRequest);

		// delete session to make sure it cannot be reused
		this.loginSessions.validateAndRemove(loginSession.getOidcLoginId());

		String codeString = loginSession.getCode();

		AuthorizationCode code = new AuthorizationCode(codeString);

		URI tokenEndpoint = oidcProviders.getTokenEndpoint(loginSession.getOidcName());

		OIDCTokenResponse tokenResponse = NimbusHelpers.tokenRequest(oidcConfig, code,
				tokenEndpoint, getScopeArray(oidcConfig), getCallbackPath(oidcConfig, serviceLocator));

		// Get the ID and access token, the server may also return a refresh token
		JWT idToken = tokenResponse.getOIDCTokens().getIDToken();
		AccessToken accessToken = tokenResponse.getOIDCTokens().getAccessToken();
		// RefreshToken refreshToken = tokenResponse.getOIDCTokens().getRefreshToken();

		String chipsterToken = createTokenFromOidc(oidcConfig, idToken, accessToken, loginSession.getNonce());

		logger.info("(8/8) OIDC login " + shorten(loginSessionId)
				+ ": login accepted and Chipster token created");

		return Response.ok(chipsterToken).build();
	}

	/**
	 * Get callback path where to browser should return after authenticating at the
	 * issuer
	 * 
	 * By default there is only a path in the configuration and we must add the app
	 * domain in front of it.
	 * 
	 * @param oidcConfig
	 * @param serviceLocator
	 * @return
	 */
	private String getCallbackPath(OidcConfig oidcConfig, ServiceLocatorClient serviceLocator) {

		String configuredPath = oidcConfig.getRedirectPath();

		if (configuredPath.startsWith("/")) {
			if (this.webServerUri == null) {
				// Get only on first request, because auth must start before service-locator.
				// The value doesn't change, so it's enough to get it once.
				this.webServerUri = serviceLocator.getPublicUri(Role.AUTH);
			}

			String combinedUri = UriBuilder.fromUri(webServerUri).path(configuredPath).build().toString();

			if (this.isDebug) {
				logger.info("combined callback url: " + combinedUri);
			}
			return combinedUri;
		}
		if (this.isDebug) {
			logger.info("configured callback url: " + configuredPath);
		}
		return configuredPath;
	}

	private String[] getScopeArray(OidcConfig oidcConfig) {
		return oidcConfig.getScope().split(" ");
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

	private void checkSourceIp(OidcLoginSession loginSession, OidcConfig oidcConfig, Request jerseyRequest) {
		if (loginSession.getSourceIp() == null) {
			logger.info("IP limit is disabled (or header was not found)");
		} else if (loginSession.getSourceIp().equals(getSourceIp(oidcConfig, jerseyRequest))) {
			if (this.isDebug) {
				logger.info("accepted source IP " + loginSession.getSourceIp());
			}
		} else {
			throw new BadRequestException("network changed");
		}
	}

	/**
	 * Validate id_token and create Chipster token
	 * 
	 * This part can be extensively tested, because here interaction with the OIDC
	 * authoriization/resource server has been (mostly) done already.
	 * 
	 * - Validate the id_token (not that critical anymore, because we get it
	 * directly from the issuer)
	 * - Optionally, get more information from the userInfo endpoint
	 * - Check that user has all claims that are required in this login method
	 * - Call method getChipsterToken()
	 * 
	 * @param oidcConfig  Configuration of the chosen login method
	 * @param idToken     id_token from the OIDC issuer to identify the user
	 * @param accessToken access_token from the OIDC issuer to get more information
	 *                    from userInfo if necessary
	 * @param nonce       The nonce which was used to generate the original
	 *                    authentication request
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

			if (accessToken == null) {
				throw new InternalServerErrorException("access token is null, cannot query userInfo");
			}

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
				// Merge claims from id_token and userInfo.
				// Use the value from id_token if the same claim is both in id_token and
				// userInfo.
				claims.putAll(idTokenClaims.getClaims());

			} catch (ParseException e) {
				throw new InternalServerErrorException("failed to parse userInfo claims", e);
			}
		} else {
			claims = idTokenClaims.getClaims();
		}

		checkClaims(oidcConfig, claims);

		String chipsterToken = getChipsterToken(claims, oidcConfig);

		return chipsterToken;
	}

	/**
	 * Get a Chipster token from the claims
	 * 
	 * At this point we trust the claims and simply collect the information that is
	 * needed for the Chipster token.
	 * 
	 * @param claims
	 * @param oidcConfig
	 * @return
	 */
	private String getChipsterToken(Map<String, Object> claims, OidcConfig oidcConfig) {

		String name = getString("name", claims);
		String email = getString("email", claims);
		Boolean emailVerified = getBoolean("email_verified", claims);

		// use different auth names in Chipster based on the claims that we get
		// claim "sub" is used by default
		String username = getStringIfFound(oidcConfig.getClaimUserId(), claims);

		String userIdPrefix = oidcConfig.getUserIdPrefix();

		if (username == null) {
			throw new ForbiddenException("username not found from claim " + oidcConfig.getClaimUserId());
		}

		UserId userId = new UserId(userIdPrefix, username);

		// store only verified emails
		if (oidcConfig.getVerifiedEmailOnly() && (emailVerified == null || emailVerified == false)) {
			if (this.isDebug) {
				logger.info("email is not saved: not verified");
			}
			email = null;
		} else if (this.isDebug) {
			logger.info("verified email");
		}

		String organization = getStringIfFound(oidcConfig.getClaimOrganization(), claims);

		userTable.addOrUpdateFromLogin(userId, email, organization, name);

		HashSet<String> roles = Stream.of(Role.CLIENT, Role.OIDC).collect(Collectors.toCollection(HashSet::new));
		String token = authTokens.createNewUserToken(userId.toUserIdString(), roles, name);

		logger.info("login successful for " + userId.toUserIdString());

		return token;
	}

	/**
	 * Check that user's claims fulfil the configured requirements for this login
	 * method
	 * 
	 * @param oidcConfig
	 * @param claims
	 */
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
			throw new ForbiddenException(oidcConfig.getRequiredClaimError());
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
	private String getStringIfFound(String claimName, Map<String, Object> claims) {
		if (!claimName.isEmpty()) {
			return getString(claimName, claims);
		}
		return null;
	}

	private String getString(String claimName, Map<String, Object> claims) {
		Object value = claims.get(claimName);
		if (value == null) {
			return null;
		}
		if (value instanceof String) {
			return (String) value;
		}
		throw new InternalServerErrorException("claim " + claimName + " value is not String: " + value);
	}

	/**
	 * Get a Boolean value from claims
	 * 
	 * @param claimName
	 * @param claims
	 * @return
	 */
	private Boolean getBoolean(String claimName, Map<String, Object> claims) {
		Object value = claims.get(claimName);
		if (value == null) {
			return null;
		}
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		throw new InternalServerErrorException("claim " + claimName + " value is not String: " + value);
	}

	private void printClaims(Map<String, Object> claims) {

		for (String k : claims.keySet()) {
			logger.info("claim " + k + ": " + claims.get(k));
		}
	}

	/**
	 * A login method can be configured to add additional parameteres to the
	 * authentication request
	 * 
	 * @param request   Request that is used to build the authentication URL
	 * @param parameter Configured parameter(s) to add
	 */
	private void addParameters(Builder request, String parameter) {
		if (parameter != null && !parameter.isEmpty()) {
			// public client in app did support multiple parameters like this
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

	private String shorten(UUID uuid) {
		if (uuid == null) {
			return null;
		}
		return uuid.toString().substring(0, 4);
	}
}
