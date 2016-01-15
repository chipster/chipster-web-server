package fi.csc.chipster.proxy;

import java.io.IOException;
import java.time.LocalDateTime;

import javax.inject.Singleton;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.proxy.ProxyServlet;

import fi.csc.chipster.proxy.model.Connection;
import fi.csc.chipster.proxy.model.Route;

/**
 * HTTP proxy servlet based on Jetty's transparent ProxyServlet
 * 
 * @author klemela
 *
 */
@Singleton
public class HttpProxyServlet extends ProxyServlet.Transparent {
	
	private final Logger logger = LogManager.getLogger();
	private ConnectionManager connectionManager;
	
	public HttpProxyServlet(ConnectionManager connectionManager) {
		this.connectionManager = connectionManager;
	}

	// collect connection information
	@Override
    protected void service(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
			
		super.service(request, response);
		// this is singleton servlet, but each request will it it's own connection object
		Connection connection = new Connection();
        connection.setSourceAddress(request.getRemoteAddr());
        connection.setRequestURI(request.getRequestURL().toString());
        connection.setRoute(new Route(getProxyPath(), getProxyTo()));
        connection.setOpenTime(LocalDateTime.now());
		
        connectionManager.addConnection(connection);
        
        // the AsyncListener can be added only after the ProxyServlet.service() has called startAsync()
        request.getAsyncContext().addListener(new AsyncListener() {
        	
        	
        	@Override
        	public void onStartAsync(AsyncEvent event) throws IOException {
        		// async is already started
        	}
        	
        	@Override
        	public void onTimeout(AsyncEvent event) throws IOException {
        		connectionManager.removeConnection(connection);
        	}
        	
        	@Override
        	public void onError(AsyncEvent event) throws IOException {
        		connectionManager.removeConnection(connection);
        	}
        	
        	@Override
        	public void onComplete(AsyncEvent event) throws IOException {
        		connectionManager.removeConnection(connection);
        	}
        });
	}
	
	// Jetty's implementation is enough, but this is a nice place for debug messages
    @Override
    protected String rewriteTarget(HttpServletRequest request)
    {        	
        String rewritten =  super.rewriteTarget(request);
        
        StringBuffer original = request.getRequestURL();    		
        logger.debug("proxy " + original  + " \t -> " + rewritten);
        return rewritten;
    }

	public String getProxyPath() {
		return this.getInitParameter(ProxyServer.PREFIX).substring(1);
	}

	public String getProxyTo() {
		return this.getInitParameter(ProxyServer.PROXY_TO);
	}
}