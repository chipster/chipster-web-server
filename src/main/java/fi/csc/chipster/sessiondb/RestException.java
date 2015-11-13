package fi.csc.chipster.sessiondb;

import java.net.URI;

import javax.ws.rs.core.Response;

public class RestException extends Exception {

	private String latestMessage;
	private Response response;
	private URI uri;
	private String body;

	public RestException(String msg, Response response, URI uri) {
		super(msg);
		this.latestMessage = msg;
		this.response = response;
		this.uri = uri;
		this.body = response.readEntity(String.class);
	}

	public RestException(String msg, Exception e) {
		super(msg, e);
		this.latestMessage = msg;
	}

	public Response getResponse() {
		return response;
	}

	public URI getUri() {
		return uri;
	}

	public String getMessage() {
		return latestMessage + " (" + response.getStatus() + ") " + body + ", " + uri;
	}

	public boolean isNotFound() {
		return response.getStatus() == 404;
	}
}
