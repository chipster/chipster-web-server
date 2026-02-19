package fi.csc.chipster.web.resource;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.text.ParseException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
@Path("")
public class OidcProxyResource {

	private static final Logger logger = LogManager.getLogger();

	private ServiceLocatorClient serviceLocator;
	private AuthenticationClient authService;

	public OidcProxyResource(ServiceLocatorClient serviceLocator, AuthenticationClient authService) {
		this.serviceLocator = serviceLocator;
		this.authService = authService;
	}

	@GET
	@Path("callback")
	// @RolesAllowed(Role.UNAUTHENTICATED)
	@Produces(MediaType.TEXT_HTML)
	public Response oidcCallback(@QueryParam("error") String error,
			@QueryParam("error_description") String error_description,
			@QueryParam("code") String code,
			@QueryParam("state") String state)
			throws GeneralSecurityException, IOException, ParseException {

		if (error != null) {
			throw new InternalServerErrorException("OIDC authentication failed, error: " + error
					+ ", error description: " + error_description + ", state: " + state);
		}

		logger.info("proxy oidc callback");

		String chipsterToken = authService.completeOidcAuthentication(code, state);

		// save Chispter token to localStorage and return to the Angular app
		String html = """
				<script>
				""";
		html += "localStorage.setItem(\"ch-auth-token\", \"" + chipsterToken + "\");\n";

		html += """
				const returnUrl = localStorage.getItem("oidcReturnUrl");

				console.log("returnUrl from localStorage: " + returnUrl);

				window.location.href = returnUrl;

				</script>
				""";

		// String idTokenString = json.get("idToken");
		// String accessTokenString = json.get("accessToken");

		// String chipsterToken = createTokenFromOidc(idTokenString, accessTokenString);

		// return Response.ok(chipsterToken).build();

		return Response.ok(html).build();
	}

	@POST
	@Path("flow")
	// @RolesAllowed(Role.UNAUTHENTICATED)
	@Produces(MediaType.TEXT_PLAIN)
	public String getAuthUrl(@QueryParam("id") String oidcName) {

		String loginUrl = authService.startOidcAuthentication(oidcName);

		logger.info("start OIDC authentication: " + loginUrl);

		return loginUrl;

		// HashMap<String, Object> json = new HashMap<>();

		// UriBuilder uriBuilder =
		// UriBuilder.fromUri(serviceLocator.getPublicUri(Role.AUTH));
		// uriBuilder.path("oidc/getConfig");

		// json.put("url", uriBuilder.build());

		// return json;
	}
}
