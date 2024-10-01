package fi.csc.chipster.rest.websocket;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import jakarta.websocket.RemoteEndpoint.Basic;

public class Subscriber {

	private Basic remote;
	private String remoteAddress;
	private String username;
	private Instant created;
	private Map<String, String> details = new HashMap<>();

	public Subscriber(Basic remote, String remoteAddress, Map<String, String> details, String username) {
		this.remote = remote;
		this.remoteAddress = remoteAddress;
		this.details = details;
		this.username = username;
		this.created = Instant.now();
	}

	public Basic getRemote() {
		return remote;
	}

	public void setRemote(Basic remote) {
		this.remote = remote;
	}

	public String getRemoteAddress() {
		return remoteAddress;
	}

	public void setRemoteAddress(String remoteAddress) {
		this.remoteAddress = remoteAddress;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public Instant getCreated() {
		return created;
	}

	public Map<String, String> getDetails() {
		return details;
	}

	public void setDetails(Map<String, String> details) {
		this.details = details;
	}
}