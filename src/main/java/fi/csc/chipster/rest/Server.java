package fi.csc.chipster.rest;

import org.glassfish.grizzly.http.server.HttpServer;

public interface Server {

	public HttpServer startServer();
	
	public void close();

	public String getBaseUri();
}
