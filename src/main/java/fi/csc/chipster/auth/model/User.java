package fi.csc.chipster.auth.model;

import java.time.Instant;

import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Version;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

@Entity // db
@XmlRootElement // json
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
	
	@Version
	private long version;
	
	private Instant	created;
	private Instant	modified;
	private Instant	accessed;
	
	
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
}
