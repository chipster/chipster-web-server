package fi.csc.chipster.web;

import java.io.IOException;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ResourceHandler;

/**
 * Forward non-handled requests to the server root
 * 
 * A single page application can invent its own URLs. For those to work on the
 * page load, the server must respond with the application (starting from the
 * index.html) when ever any of those URLs is used. Then the application can
 * handle the URL itself. In practice we can't know what URLs the application is
 * going to handle but can simply forward all non-handled responses.
 * 
 * @author klemela
 */
public class ForwardingResourceHandler extends ResourceHandler {

	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {

		super.handle(target, baseRequest, request, response);
		
		if (!baseRequest.isHandled()) {
			String redirectRoute = "/";
			RequestDispatcher dispatcher = request.getRequestDispatcher(redirectRoute);
			dispatcher.forward(request, response);
		}
	}
}