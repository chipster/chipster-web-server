package fi.csc.chipster.shibboleth;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.BadRequestException;
import javax.xml.bind.DatatypeConverter;

import org.apache.http.client.utils.URIBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;


public class ShibbolethServlet extends HttpServlet {

	private static final String DEBUG = "debug";
	private static final String APP_ROUTE = "appRoute";

	private static final Logger logger = LogManager.getLogger();

	private AuthenticationClient authService;

	private String webAppUrl;

	public ShibbolethServlet(AuthenticationClient authService, ServiceLocatorClient serviceLocator) {
		this.authService = authService;
		this.webAppUrl = serviceLocator.getPublicUri(Role.WEB_SERVER);
				
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		
		// add query parameter "debug" to disable the redirect
		boolean debug = request.getParameterMap().containsKey(DEBUG);
		String appRoute = request.getParameter(APP_ROUTE);

		String eppn = fixEncoding(request.getAttribute("SHIB_eppn"));
		String cn = fixEncoding(request.getAttribute("SHIB_cn"));
		String mail = fixEncoding(request.getAttribute("SHIB_mail"));
		String o = fixEncoding(request.getAttribute("SHIB_o"));
		String schachHomeOrganization = fixEncoding(request.getAttribute("SHIB_schachHomeOrganization"));
		
		String org = o != null ? o : schachHomeOrganization;
		
		User user = new User(eppn, mail, org, cn);	
				
		Token token;
		try {
			token = authService.ssoLogin(user);
		} catch (BadRequestException e) {
			// BadRequests aren't logged by default
			// comment out this try block to test this servlet locally without shibboleth attributes
			logger.error("sso login failed: " + e.getResponse().readEntity(String.class));
			throw e;
		}
		
		String loggedInUrl;
		try {
			URIBuilder loggedInUrlBuilder = new URIBuilder(webAppUrl + "/" + appRoute + "/login");
		
			// keep all other query parameters in the url
			request.getParameterMap().keySet().stream()
				.filter(p -> !p.equals(APP_ROUTE))
				.filter(p -> !p.equals(DEBUG))
				.forEach(p -> loggedInUrlBuilder.addParameter(p, request.getParameter(p)));
			
			loggedInUrl = loggedInUrlBuilder.build().toString();
		} catch (URISyntaxException e) {
			throw new ServletException("failed to build the return url", e);
		}
				
		try (ServletOutputStream out = response.getOutputStream()) {
			response.setContentType("text/html;charset=UTF-8");
			
			String tokenJson = RestUtils.asJson(token);
			
			/*
			 * Save token to the local storage
			 * 
			 * Browsers allow this only if this servlet is served from the same domain where the token is used. 
			 * It would be possible to circumvent this restrictions with an iframe, but even that doesn't work in Safari.  
			 */
			
			String htmlStart = "\n"
					+ "<html>\n"
					+ "<head>\n"
					+ "<!-- disable IE's compatibility mode for intranet sites. Must be the first meta tag.-->\n"
					+ "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n"
					+ "<script>\n";

			// encode and decode variables with base64 to make it safe to embed them to the js code (the values can't escape
			// from the quotes without special characters)
			String jsVars = "\n"
					+ "let tokenString = atob('" + DatatypeConverter.printBase64Binary(tokenJson.getBytes()) + "');\n"
					+ "let loggedInUrl = atob('" + DatatypeConverter.printBase64Binary(loggedInUrl.getBytes()) + "');\n"
					+ "let debug = " + debug + ";\n"
					+ "let token = JSON.parse(tokenString);\n";
			
			String jsCode = "\n"					
					+ "window.localStorage.setItem('ch-auth-token', token.tokenKey);\n"
					+ "window.localStorage.setItem('ch-auth-username', token.username);\n"
					+ "window.localStorage.setItem('ch-auth-valid-until', token.validUntil);\n"
					+ "window.localStorage.setItem('ch-auth-roles', token.rolesJson);\n"
					+ "\n"
					+ "	if (!debug) {\n"
					+ "		console.log('redirecting to', loggedInUrl);\n"
					+ "		// redirect to the app without saving this page to the session history\n"
					+ "		window.location.replace(loggedInUrl);\n"
					+ "	}\n"
					+ "\n";										
					
			String htmlEnd = "\n"
					+ "</script>\n"
					+ "</head>\n"
					+ "<body>\n"
					+ " Save token...<br>\n"		
					+ "	<a href='" + loggedInUrl + "'>Continue to the app...</a>\n"
					+ "</body>\n"
					+ "</html>\n";
						
			out.print(htmlStart + jsVars + jsCode + htmlEnd);
		}
	}

	public static String fixEncoding(Object object) throws UnsupportedEncodingException {
		if ( object == null) {
			return null;
		}
		return new String( object.toString().getBytes("ISO-8859-1"), "UTF-8");
	}
}


