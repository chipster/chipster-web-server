package fi.csc.chipster.jobhistory;

import java.util.HashMap;
import java.util.List;

import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestMethods;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.model.JobIdPair;

public class JobHistoryClient {

	private static final Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private ServiceLocatorClient serviceLocator;
	private CredentialsProvider credentials;

	private WebTarget jobHistoryTarget;

	public JobHistoryClient(ServiceLocatorClient serviceLocator,
			CredentialsProvider credentials) {
		this.serviceLocator = serviceLocator;
		this.credentials = credentials;

		String jobHistoryUri = serviceLocator.getInternalService(
				Role.JOB_HISTORY).getAdminUri();
		System.out.println(" Creating Job History test client" + jobHistoryUri);
		if (credentials != null) {
			jobHistoryTarget = AuthenticationClient.getClient(
					this.credentials.getUsername(),
					this.credentials.getPassword(), true).target(jobHistoryUri);
		}
	}

	private WebTarget getJobHistoryTarget() {
		return jobHistoryTarget.path("admin/jobhistory");
	}

	// methods
	public HashMap<JobIdPair, JobHistory> getJobHistoryList()
			throws RestException {
		logger.info("JobHistory target" + getJobHistoryTarget());
		List<JobHistory> jobHistoryList = RestMethods.getList(
				getJobHistoryTarget(), JobHistory.class);

		HashMap<JobIdPair, JobHistory> map = new HashMap<>();

		for (JobHistory js : jobHistoryList) {
			map.put(js.getJobIdPair(), js);
		}

		return map;
	}

	public Response get() throws RestException {
		Response response = getJobHistoryTarget().request().get(Response.class);
		return response;
	}

	public void saveTestJob(JobHistory jobHistory) throws RestException {
		logger.info("Target uri" + getJobHistoryTarget());
		RestMethods.put(getJobHistoryTarget(), jobHistory);
	}
}
