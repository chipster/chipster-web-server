package fi.csc.chipster.rest.websocket;

import java.time.LocalDateTime;

import javax.websocket.RemoteEndpoint.Basic;

public class Subscriber {

	private Basic remote;
	private String remoteAddress;
	private String username;
	private LocalDateTime created;

	public Subscriber(Basic remote, String remoteAddress, String username) {
		this.remote = remote;
		this.remoteAddress = remoteAddress;
		this.setUsername(username);
		this.created = LocalDateTime.now();
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

	public LocalDateTime getCreated() {
		return created;
	}
}