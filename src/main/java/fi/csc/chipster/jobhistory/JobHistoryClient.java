package fi.csc.chipster.jobhistory;



import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestMethods;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;

public class JobHistoryClient {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private ServiceLocatorClient serviceLocator;
	private List<String> jobHistoryList;
	private CredentialsProvider credentials;

	private WebTarget jobHistoryTarget;

	public JobHistoryClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials) {
		this.serviceLocator = serviceLocator;
		this.credentials = credentials;
		
		init(serviceLocator.getPublicUri(Role.JOB_HISTORY));	
			
	}
	
	
	private void init(String jobHistoryTargetUri) {
		System.out.println("the target uri"+jobHistoryTargetUri);
		if (credentials != null) {
			jobHistoryTarget = AuthenticationClient.getClient(credentials.getUsername(), credentials.getPassword(), true).target(jobHistoryTargetUri);
		} else {
			// for testing
			jobHistoryTarget = AuthenticationClient.getClient().target(jobHistoryTargetUri);
		}
	}
	
	
	// targets
	
	private WebTarget getJobHistoryTarget() {
		return jobHistoryTarget.path("jobhistory");
	}
	
	private WebTarget getJobByIDTarget(UUID id){
		return getJobHistoryTarget().path(id.toString());
	}
	
	// methods 
	public HashMap<UUID, JobHistoryModel> getJobHistoryList() throws RestException {		
		System.out.println("JobHistory target"+getJobHistoryTarget());
		List<JobHistoryModel> jobHistoryList = RestMethods.getList(getJobHistoryTarget(), JobHistoryModel.class);
		
		HashMap<UUID, JobHistoryModel> map = new HashMap<>();
		
		for (JobHistoryModel js : jobHistoryList) {
			map.put(js.getJobId(), js);
		}
		
		return map;
	}
	
	public Response get() throws RestException {
		Response response = getJobHistoryTarget().request().get(Response.class);	
		return response;
	}
	
	
	public UUID getJobByID(UUID jobId) throws RestException{
		JobHistoryModel js=RestMethods.get(getJobByIDTarget(jobId), JobHistoryModel.class);
		System.out.println("got Job"+ js.getJobId());
		return js.getJobId();
	
	}
	
	
	
	
	

	
}
