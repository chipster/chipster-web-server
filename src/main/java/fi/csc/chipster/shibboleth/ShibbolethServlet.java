package fi.csc.chipster.shibboleth;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.DatatypeConverter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;


public class ShibbolethServlet extends HttpServlet {

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	private AuthenticationClient authService;

	private String webAppUrl;

	public ShibbolethServlet(AuthenticationClient authService, ServiceLocatorClient serviceLocator) {
		this.authService = authService;
		this.webAppUrl = serviceLocator.getService(Role.WEB_SERVER).getPublicUri();
				
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		String eppn = fixEncoding(request.getAttribute("SHIB_eppn"));
		String cn = fixEncoding(request.getAttribute("SHIB_cn"));
		String mail = fixEncoding(request.getAttribute("SHIB_mail"));
		String o = fixEncoding(request.getAttribute("SHIB_o"));
		String schachHomeOrganization = fixEncoding(request.getAttribute("SHIB_schachHomeOrganization"));
		
		String org = o != null ? o : schachHomeOrganization;
		
		User user = new User(eppn, mail, org, cn);	
		
		Token token = authService.ssoLogin(user);
		
		String saveTokenUrl = webAppUrl + "/assets/save-token.html";
		String loggedInUrl = webAppUrl + "/sessions";
				
		try (ServletOutputStream out = response.getOutputStream()) {
			response.setContentType("text/html;charset=UTF-8");
			
			String tokenJson = RestUtils.asJson(token);
			// encode the token object with base64 to make it safe to embed it to the js code (can't escape
			// from the quotes without special characters)
			String tokenBase64 = DatatypeConverter.printBase64Binary(tokenJson.getBytes());
			
			/* Send token to the Angular app
			 * 
			 * We can't set the token to the app's LocalStorage directly, because this is service
			 * is in a different (sub)domain. We don't want to send the token in the query 
			 * parameter, because it would stay in the page history. We have to open an iframe 
			 * to the app's domain and post the token there. 
			 */
			
			out.print("<html><head><script>\n"
					+ "var token = atob('" + tokenBase64 + "');\n"
					+ "console.log('save token', token);\n"
					+ " \n"
					+ "window.onload = function() {\n"
					+ "    var win = document.getElementsByTagName('iframe')[0].contentWindow;\n"
					+ "    \n"
					+ "    // don't allow the token to be send anywhere else\n"
					+ "    win.postMessage(token, '" + webAppUrl + "');\n"
					+ "    \n"
					+ "    // redirect to the app without saving this page to the session history\n"
					+ "    window.location.replace('" + loggedInUrl + "');\n"
					+ "};\n"
					+ " \n"					
					+ "</script></head>\n"
					+ "<body>\n"
					+ " Post token to " + webAppUrl + "...<br>\n"		
					+ "	<iframe src='" + saveTokenUrl + "' frameborder='0'>\n"
					+ "</body></html>\n");
		}
	}

	public static String fixEncoding(Object object) throws UnsupportedEncodingException {
		if ( object == null) {
			return null;
		}
		return new String( object.toString().getBytes("ISO-8859-1"), "UTF-8");
	}
}


