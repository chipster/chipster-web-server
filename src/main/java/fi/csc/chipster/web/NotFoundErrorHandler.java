package fi.csc.chipster.web;

import java.io.IOException;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ErrorHandler;

/**
 * Forward 404 to the server root
 * 
 * A single page application can invent its own URLs. For those to work on the
 * page load, the server must respond with the application (starting from the
 * index.html) when ever any of those URLs is used. Then the application can
 * handle the URL itself. In practice we can't know what URLs the application is
 * going to handle but can simply forward all 404 responses.
 * 
 * @author klemela
 */
public class NotFoundErrorHandler extends ErrorHandler {

	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
			throws IOException {

		// Handle 404 and only if the request isn't forwarded already.
		// Otherwise there is an endless loop of forwards.
		if ((response.getStatus() == HttpServletResponse.SC_NOT_FOUND)
				&& baseRequest.getDispatcherType() != DispatcherType.FORWARD) {

			try {
				// On 404 redirect to the root (which will redirect onward to
				// index.html)
				String redirectRoute = "/";
				RequestDispatcher dispatcher = request.getRequestDispatcher(redirectRoute);
				// set response code back to 200
				response.reset();
				dispatcher.forward(request, response);
			} catch (ServletException e) {
				super.handle(target, baseRequest, request, response);
			}
		} else {
			super.handle(target, baseRequest, request, response);
		}
	}
}