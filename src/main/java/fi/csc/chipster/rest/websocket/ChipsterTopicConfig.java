package fi.csc.chipster.rest.websocket;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.UserToken;
import fi.csc.chipster.auth.resource.AuthPrincipal;

public abstract class ChipsterTopicConfig implements TopicConfig {
	
	private AuthenticationClient authService;

	public ChipsterTopicConfig(AuthenticationClient authService) {
		this.authService = authService;
	}

	@Override
	public AuthPrincipal getUserPrincipal(String token) {

		UserToken validToken = authService.validateUserToken(token);
		
		if (validToken != null) {    		
			return new AuthPrincipal(validToken, token);
		} else {
			return null;
		}    		
	}
}
