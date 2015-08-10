package fi.csc.chipster.sessionstorage.model;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement // REST
public class SessionEvent {

	private String sessionEventId;
	private EventType type;
	
	public enum EventType { CREATE, UPDATE, DELETE }

	public SessionEvent(String id, EventType type) {
		this.sessionEventId = id;
		this.type = type;
	}

	public String getSessionEventId() {
		return sessionEventId;
	}

	public void setSessionEventId(String id) {
		this.sessionEventId = id;
	}

	public EventType getType() {
		return type;
	}

	public void setType(EventType type) {
		this.type = type;
	}
}
