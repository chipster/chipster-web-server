package fi.csc.chipster.auth.model;

import java.time.Instant;
import java.util.Set;

import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * User token authenticates user
 * 
 * This token authenticates user as the value of the username field (actually 
 * in the superclass). 
 * 
 * This token class is used also for service accounts used by the servers.
 * 
 * These tokens allow the full access to all sessions of a specific user. Use more
 * restricted DatasetTokens and SessionTokens when there is 
 * a risk that this UserToken could leak, for example in the download urls. 
 * 
 * @author klemela
 *
 */
@XmlRootElement // json
public class UserToken extends ChipsterToken {
		
	private Instant	created;
	private String name;

	public UserToken() {
		// JAX-B needs this
	}
	
	public UserToken(String username, 
			Instant validUntil, Instant created, Set<String> roles) {
		
		super(username, validUntil, roles);

		this.created = created;
	}
		
	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}
		
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
