package fi.csc.chipster.sessionstorage.rest;

import org.glassfish.grizzly.http.server.HttpServer;

public interface Server {

	public HttpServer startServer();
	
	public String getBaseUri();
}
