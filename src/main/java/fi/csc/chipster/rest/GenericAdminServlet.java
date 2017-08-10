package fi.csc.chipster.rest;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;


public class GenericAdminServlet extends HttpServlet {

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();
		
	private AuthenticationClient authService;

	public GenericAdminServlet(AuthenticationClient authService) {

		this.authService = authService;
	}
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		
		HashMap<String, Object> status = new HashMap<>();		
		
		if (ServletUtils.isInRole(Role.ADMIN, request, authService)) {
			status.putAll(GenericAdminResource.getSystemStats());
		}
		
		status.put(GenericAdminResource.KEY_STATUS, GenericAdminResource.VALUE_OK);
		
		response.setContentType("application/json");
		
		PrintWriter out = response.getWriter();
		out.println(RestUtils.asJson(status));
	}		
}
