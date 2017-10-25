package fi.csc.chipster.rest.websocket;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.AuthPrincipal;

public abstract class ChipsterTopicConfig implements TopicConfig {
	
	private AuthenticationClient authService;

	public ChipsterTopicConfig(AuthenticationClient authService) {
		this.authService = authService;		
	}

	@Override
	public AuthPrincipal getUserPrincipal(String tokenKey) {
		
		Token dbToken = authService.getDbToken(tokenKey);
		
		if (dbToken != null) {    		
			return new AuthPrincipal(dbToken.getUsername(), dbToken.getRoles());
		} else {
			return null;
		}    		
	}
}
