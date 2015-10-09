package fi.csc.chipster.sessiondb.model;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement // REST
public class SessionEvent {

	private UUID sessionId;
	private ResourceType resource;
	private EventType type;
	private String serverId;
	private long eventNumber;
	private UUID resourceId;
	
	public enum EventType { CREATE, UPDATE, DELETE }
	public enum ResourceType { SESSION, DATASET, JOB }

	public SessionEvent(UUID sessionId, ResourceType resource, UUID resourceId, EventType type) {
		this.resource = resource;
		this.sessionId = sessionId;
		this.resourceId = resourceId;
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

	public UUID getSessionId() {
		return sessionId;
	}

	public void setSessionId(UUID sessionId) {
		this.sessionId = sessionId;
	}

	public ResourceType getResourceType() {
		return resource;
	}

	public void setResourceType(ResourceType resourceType) {
		this.resource = resourceType;
	}

	public UUID getResourceId() {
		return resourceId;
	}

	public void setResourceId(UUID resourceId) {
		this.resourceId = resourceId;
	}
}
