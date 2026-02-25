package fi.csc.chipster.auth.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlRootElement;

@Entity // db
@XmlRootElement // json
@Table
public class OidcLoginSession {

	@Id
	private UUID oidcLoginId;
	private String state;
	private String nonce;
	private Instant created;
	private String oidcName;

	public OidcLoginSession(UUID oidcLoginId, String state, String nonce, Instant created, String oidcName) {
		this.oidcLoginId = oidcLoginId;
		this.state = state;
		this.nonce = nonce;
		this.created = created;
		this.oidcName = oidcName;
	}

	public String getOidcName() {
		return oidcName;
	}

	public void setOidcName(String oidcName) {
		this.oidcName = oidcName;
	}

	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}

	public UUID getOidcLoginId() {
		return oidcLoginId;
	}

	public void setOidcLoginId(UUID oidcLoginId) {
		this.oidcLoginId = oidcLoginId;
	}

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getNonce() {
		return nonce;
	}

	public void setNonce(String nonce) {
		this.nonce = nonce;
	}

	public OidcLoginSession() {
		// JAX-B needs this
	}
}
