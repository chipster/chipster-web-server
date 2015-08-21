package fi.csc.chipster.rest;

import org.glassfish.grizzly.http.server.HttpServer;

public interface Server {

	public HttpServer startServer();
	
	public String getBaseUri();

	public void close();
}
