package fi.csc.chipster.scheduler.offer;

import java.util.Arrays;
import java.util.List;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.websocket.ChipsterTopicConfig;

public class SchedulerTopicConfig extends ChipsterTopicConfig {
	
	public SchedulerTopicConfig(AuthenticationClient authService) {
		super(authService);
	}

	private static final String TOPIC_GROUP_SERVER = ",topicGroup=server";
	
	@Override
	public boolean isAuthorized(AuthPrincipal principal, String topic) {
		// check the authorization of the connecting comps
		return principal.getRoles().contains(Role.COMP);			
	}
    
	@Override
	public String getMonitoringTag(String topicName) {
		return TOPIC_GROUP_SERVER;
	}
	
	@Override
	public List<String> getMonitoringTags() {
		return Arrays.asList(new String[] {TOPIC_GROUP_SERVER});
	}

}
