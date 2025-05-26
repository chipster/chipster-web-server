package fi.csc.chipster.scheduler;

import java.util.HashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.RestUtils;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;

public class SchedulerClient {

	private String baseUri;

	public SchedulerClient(String schedulerUri) {
		this.baseUri = schedulerUri;
	}

	public HashMap<String, Object> getJobQuota() throws JsonMappingException, JsonProcessingException {

		WebTarget serviceTarget = AuthenticationClient.getClient().target(baseUri).path("jobQuota");

		String json = serviceTarget.request(MediaType.APPLICATION_JSON).get(String.class);

		return RestUtils.parseJsonToMap(json);
	}
}
