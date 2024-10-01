package fi.csc.chipster.sessiondb;

import java.net.URI;

import jakarta.ws.rs.core.Response;

public class RestException extends Exception {

	private String latestMessage;
	private Response response;
	private URI uri;
	private String body;
	private Integer status;

	public RestException(String msg, Response response, URI uri) {
		super(msg);
		this.latestMessage = msg;
		this.response = response;
		this.status = response.getStatus();
		this.uri = uri;
		this.body = response.readEntity(String.class);
	}

	public RestException(String msg, Exception e) {
		super(msg, e);
		this.latestMessage = msg;
	}

	public RestException(String msg) {
		super(msg);
	}

	public RestException(String msg, org.eclipse.jetty.client.Response response2, URI uri2) {

		super(msg);
		this.latestMessage = msg;
		this.status = response2.getStatus();
		this.uri = uri2;
	}

	public Response getResponse() {
		return response;
	}

	public URI getUri() {
		return uri;
	}

	public String getMessage() {
		String msg = latestMessage;
		if (status != null) {
			msg += " (" + status + ")";
		}
		msg += " " + body + ", " + uri;
		return msg;
	}

	public boolean isNotFound() {
		return response.getStatus() == 404;
	}

	public Integer getStatus() {
		return status;
	}
}
