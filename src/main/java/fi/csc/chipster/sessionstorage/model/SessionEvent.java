package fi.csc.chipster.sessionstorage.model;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement // REST
public class SessionEvent {

	private String sessionId;
	private ResourceType resource;
	private EventType type;
	private String serverId;
	private long eventNumber;
	
	public enum EventType { CREATE, UPDATE, DELETE }
	public enum ResourceType { SESSION, DATASET, JOB }

	public SessionEvent(String sessionId, ResourceType resource, EventType type) {
		this.resource = resource;
		this.sessionId = sessionId;
		this.type = type;
	}
	
	public SessionEvent() {
		// JAXB needs this
	}

	public EventType getType() {
		return type;
	}

	public void setType(EventType type) {
		this.type = type;
	}

	public String getServerId() {
		return serverId;
	}

	public void setServerId(String serverId) {
		this.serverId = serverId;
	}

	public long getEventNumber() {
		return eventNumber;
	}

	public void setEventNumber(long eventNumber) {
		this.eventNumber = eventNumber;
	}

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public ResourceType getResourceType() {
		return resource;
	}

	public void setResourceType(ResourceType resourceType) {
		this.resource = resourceType;
	}
}
