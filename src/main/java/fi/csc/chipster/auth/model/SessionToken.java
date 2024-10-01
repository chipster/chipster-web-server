package fi.csc.chipster.auth.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * Session token allows access to specific session for a limited period of time
 * 
 * The access may be read-only or read-write. The app creates read-only token
 * for example to create a session download url. Scheduler creates read-write
 * tokens
 * for jobs so that those can download the input data, update the job object and
 * finally
 * upload the result files.
 * 
 * @author klemela
 *
 */
@XmlRootElement // json
public class SessionToken extends ChipsterToken {

	public enum Access {
		READ_ONLY, READ_WRITE
	}

	private UUID sessionId;
	private Access access;

	public SessionToken() {
		/* for JSON */ }

	public SessionToken(String username, UUID sessionId, Instant valid, Access access) {

		super(username, valid, Role.SESSION_TOKEN);

		this.sessionId = sessionId;
		this.access = access;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public void setSessionId(UUID sessionId) {
		this.sessionId = sessionId;
	}

	public Access getAccess() {
		return access;
	}

	public void setAccess(Access access) {
		this.access = access;
	}
}
