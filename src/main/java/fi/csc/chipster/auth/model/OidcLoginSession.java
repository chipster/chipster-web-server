package fi.csc.chipster.auth.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
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
	private String sourceIp;
	@Lob
	private String code;

	public OidcLoginSession() {
	}

	public OidcLoginSession(UUID oidcLoginId, Instant created, String oidcName,
			String sourceIp) {

		this.oidcLoginId = oidcLoginId;
		this.created = created;
		this.oidcName = oidcName;
		this.sourceIp = sourceIp;
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

	public String getSourceIp() {
		return sourceIp;
	}

	public void setSourceIp(String sourceIp) {
		this.sourceIp = sourceIp;
	}

	public String getCode() {
		return this.code;
	}

	public void setCode(String code) {
		this.code = code;
	}
}
