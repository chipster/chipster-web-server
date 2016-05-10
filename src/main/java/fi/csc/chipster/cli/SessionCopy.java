package fi.csc.chipster.cli;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Session;

public class SessionCopy {
	
	private static String proxy;
	private static CredentialsProvider credentials;
	private static RestFileBrokerClient fileBrokerClient;
	private static SessionDbClient sessionDbClient;
	private static String sessionDir;	
	
	public static void main(String args[]) throws InterruptedException {
		
		proxy = "http://localhost:8000/";
		
		String username = "client";
		String password = "clientPassword";
		sessionDir = args[0];
		
		if (sessionDir == null) {
			System.out.println("usage: SessionCopy SESSION_DIR");
			System.exit(1);
		}
		
		String authURI = proxy + "auth/";
		credentials = new AuthenticationClient(authURI, username, password).getCredentials();
		fileBrokerClient = new RestFileBrokerClient(proxy + "filebroker", credentials);
		sessionDbClient = new SessionDbClient(proxy + "sessiondb/", proxy + "sessiondbevents/", credentials);
		
		int threads = 10;
		
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		
		long t = System.currentTimeMillis();
		
		for (int i = 0; i < 10; i++) {
			pool.execute(getCopyRunnable(i));
		}
		
		pool.shutdown();
		pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
		
		System.out.println(((System.currentTimeMillis() - t) / (float)threads) + "ms");
		
		System.exit(0);
	}
	
	
	private static Runnable getCopyRunnable(final int i) {
		return new Runnable() {
			@Override
			public void run() {
				try {
					System.out.println(i);
					copy(i);
				} catch (RestException | IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		};
	}


	public static void copy(int i) throws RestException, IOException {
		String name = "session" + i;
		File jobLinks = new File(sessionDir, "jobs");
		File datasetLinks = new File(sessionDir, "datasets");
		File fileLinks = new File(sessionDir, "files");
		File jobs = new File(jobLinks, "UUID");
		File datasets = new File(datasetLinks, "UUID");
		File files = new File(fileLinks, "UUID");
		
		Session session = RestUtils.parseJson(Session.class, FileUtils.readFileToString(new File(sessionDir,  "session.json")));
		if (name != null) {
			session.setName(name);
		}	
		
		session.setSessionId(null);
		UUID sessionId = getSessionDbClient().createSession(session);
		
		for (File file : datasets.listFiles()) {
			Dataset dataset = RestUtils.parseJson(Dataset.class, FileUtils.readFileToString(file));
			dataset.setDatasetId(null);
			dataset.setFile(null);
			getSessionDbClient().createDataset(sessionId, dataset);
			getFileBrokerClient().upload(sessionId, dataset.getDatasetId(), new File(files, file.getName()));
		}
		
		for (File file : jobs.listFiles()) {
			Job job = RestUtils.parseJson(Job.class, FileUtils.readFileToString(file));
			job.setJobId(null);
			getSessionDbClient().createJob(sessionId, job);
		}
	}
	
	private static RestFileBrokerClient getFileBrokerClient() {
		return fileBrokerClient;
	}

	private static SessionDbClient getSessionDbClient() {
		return sessionDbClient;
	}
}
