package fi.csc.chipster.sessiondb.model;

import java.util.UUID;

import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement // REST
public class SessionEvent {

	private UUID sessionId;
	private ResourceType resource;
	/**
	 * Generic event type, e.g. an object was created, updated or deleted
	 */
	private EventType type;
	private String serverId;
	private long eventNumber;
	private UUID resourceId;
	/**
	 * Object specific state, e.g. session is being imported or job is running
	 */
	private String state;

	public enum EventType {
		CREATE, UPDATE, DELETE
	}

	public enum ResourceType {
		RULE, DATASET, JOB, FILE, SESSION, NEWS
	}

	public SessionEvent(UUID sessionId, ResourceType resource, UUID resourceId, EventType type) {
		this(sessionId, resource, resourceId, type, null);
	}

	public <E extends Enum<E>> SessionEvent(UUID sessionId, ResourceType resource, UUID resourceId, EventType type,
			Enum<E> state) {
		this.resource = resource;
		this.sessionId = sessionId;
		this.resourceId = resourceId;
		this.type = type;
		if (state != null) {
			this.setState(state.toString());
		}
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

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}
}
