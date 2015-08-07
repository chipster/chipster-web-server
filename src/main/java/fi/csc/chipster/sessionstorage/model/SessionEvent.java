package fi.csc.chipster.sessionstorage.model;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement // REST
public class SessionEvent {

	private String id;
	private EventType type;
	
	public enum EventType { CREATE, UPDATE, DELETE }

	public SessionEvent(String id, EventType type) {
		this.id = id;
		this.type = type;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public EventType getType() {
		return type;
	}

	public void setType(EventType type) {
		this.type = type;
	}
}
