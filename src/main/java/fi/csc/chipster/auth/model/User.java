package fi.csc.chipster.auth.model;

import java.time.Instant;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.Version;

import org.hibernate.annotations.Type;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;

import fi.csc.chipster.rest.hibernate.JsonNodeJsonType;
import jakarta.xml.bind.annotation.XmlRootElement;

@Entity // db
@XmlRootElement // json
@Table(name = "user_table") // user is reserved word in Postgres
public class User {		
		
	@EmbeddedId // db
	@JsonUnwrapped // rest
	private UserId userId;

	private String mail;
	private String organization;
	private String name;
	
	// terms of use
	private int termsVersion;
	private Instant termsAccepted;
	
	private UUID latestSession;

	@Version
	private long version;
	
	private Instant	created;
	private Instant	modified;
	private Instant	accessed;
	
	@Column
	@Type(type = JsonNodeJsonType.JSON_NODE_JSON_TYPE)
	private JsonNode preferences;
	
	
	public User() {
		// JAX-B needs this
	}

	public User(String username, String mail, String organization, String name) {
		this(null, username, mail, organization, name);
	}
	
	public User(String auth, String username, String mail, String organization, String name) {
		this.userId = new UserId(auth, username);
		this.mail = mail;
		this.organization = organization;
		this.name = name;
	}

	public String getMail() {
		return mail;
	}


	public void setMail(String mail) {
		this.mail = mail;
	}

	public UUID getLatestSession() {
		return latestSession;
	}

	public void setLatestSession(UUID latestSession) {
		this.latestSession = latestSession;
	}


	public String getOrganization() {
		return organization;
	}


	public void setOrganization(String organization) {
		this.organization = organization;
	}


	public long getVersion() {
		return version;
	}


	public void setVersion(long version) {
		this.version = version;
	}

	public Instant getCreated() {
		return created;
	}


	public void setCreated(Instant created) {
		this.created = created;
	}


	public Instant getModified() {
		return modified;
	}


	public void setModified(Instant modified) {
		this.modified = modified;
	}


	public Instant getAccessed() {
		return accessed;
	}


	public void setAccessed(Instant accessed) {
		this.accessed = accessed;
	}

	public String getName() {
		return name;
	}


	public void setName(String name) {
		this.name = name;
	}

	public UserId getUserId() {
		return userId;
	}

	public void setUserId(UserId userId) {
		this.userId = userId;
	}

	public int getTermsVersion() {
		return termsVersion;
	}

	public void setTermsVersion(int termsVersion) {
		this.termsVersion = termsVersion;
	}

	public Instant getTermsAccepted() {
		return termsAccepted;
	}

	public void setTermsAccepted(Instant termsAccepted) {
		this.termsAccepted = termsAccepted;
	}

	public JsonNode getPreferences() {
		return preferences;
	}

	public void setPreferences(JsonNode preferences) {
		this.preferences = preferences;
	}
}
