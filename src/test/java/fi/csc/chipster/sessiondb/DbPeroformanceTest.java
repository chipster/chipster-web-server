package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Session;

public class DbPeroformanceTest {

	private static TestServerLauncher launcher;
	private static int n = 10;
	private final static int threads = 2;
	private static Queue<UUID> sessionIds;
	private static Queue<UUID> datasetIds;
	private static SessionDbClient client;
	private static UUID datasetSessionId;
	private static Queue<UUID> jobIds;

	@BeforeAll
	public static void setUpBeforeClass() throws Exception {
		// once per class
		Config config = new Config();
		launcher = new TestServerLauncher(config);

		client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		sessionIds = postSessionsParallel();

		datasetSessionId = client.createSession(RestUtils.getRandomSession());
		datasetIds = postDatasetsParallel(datasetSessionId);
		jobIds = postJobsParallel(datasetSessionId);
	}

	@AfterAll
	public static void tearDownAfterClass() throws IOException, InterruptedException {
		launcher.stop();
	}

	// @Test
	public void postSession() throws RestException {
		client.createSession(RestUtils.getRandomSession());
	}

	// @Test
	public void postDataset() throws RestException {
		UUID sessionId = client.createSession(RestUtils.getRandomSession());
		client.createDataset(sessionId, RestUtils.getRandomDataset());
	}

	@Test
	public void getDataset() throws RestException {
		client.getDataset(datasetSessionId, datasetIds.peek());
	}

	@Test
	public void postDatasetsParallelTest() throws IOException, InterruptedException, RestException {
		postDatasetsParallel(client.createSession(RestUtils.getRandomSession()));
	}

	public static Queue<UUID> postDatasetsParallel(UUID sessionId)
			throws IOException, InterruptedException, RestException {
		final Queue<UUID> ids = new ConcurrentLinkedQueue<>();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		for (int i = 0; i < n; i++) {
			executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						Dataset d = RestUtils.getRandomDataset();
						ids.add(client.createDataset(sessionId, d));
					} catch (RestException e) {
						e.printStackTrace();
					}
				}
			});
		}
		executor.shutdown();
		boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
		assertEquals(false, timeout);
		return ids;
	}

	@Test
	public void postJobsParallelTest() throws IOException, InterruptedException, RestException {
		postDatasetsParallel(client.createSession(RestUtils.getRandomSession()));
	}

	public static Queue<UUID> postJobsParallel(UUID sessionId) throws IOException, InterruptedException, RestException {
		final Queue<UUID> ids = new ConcurrentLinkedQueue<>();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		for (int i = 0; i < n; i++) {
			executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						Job j = RestUtils.getRandomJob();
						ArrayList<Input> inputs = new ArrayList<>();

						for (int i = 0; i < 10; i++) {
							Input input = new Input();
							UUID datasetId = (UUID) datasetIds.toArray()[i];
							input.setDatasetId(datasetId.toString());
							input.setInputId("input" + i);
							inputs.add(input);
						}
						j.setInputs(inputs);

						ArrayList<Parameter> parameters = new ArrayList<>();
						for (int i = 0; i < 10; i++) {
							Parameter p = new Parameter();
							p.setParameterId("param" + i);
							p.setValue("value" + i);
							parameters.add(p);
						}
						j.setParameters(parameters);

						ids.add(client.createJob(sessionId, j));
					} catch (RestException e) {
						e.printStackTrace();
					}
				}
			});
		}
		executor.shutdown();
		boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
		assertEquals(false, timeout);
		return ids;
	}

	@Test
	public void postSessionsSerial() throws RestException {
		for (int i = 0; i < n; i++) {
			client.createSession(RestUtils.getRandomSession());
		}
	}

	@Test
	public void postSessionsParallelTest() throws IOException, InterruptedException {
		postSessionsParallel();
	}

	public static Queue<UUID> postSessionsParallel() throws IOException, InterruptedException {

		final Queue<UUID> ids = new ConcurrentLinkedQueue<>();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		for (int i = 0; i < n; i++) {
			executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						ids.add(client.createSession(RestUtils.getRandomSession()));
					} catch (RestException e) {
						e.printStackTrace();
					}
				}
			});
		}
		executor.shutdown();
		boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
		assertEquals(false, timeout);
		return ids;
	}

	@Test
	public void getSessionsParallel() throws IOException, InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		for (final UUID id : sessionIds) {
			executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						client.getSession(id);
					} catch (RestException e) {
						e.printStackTrace();
					}
				}
			});
		}
		executor.shutdown();
		boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
		assertEquals(false, timeout);
	}

	@Test
	public void putSessionsParallel() throws IOException, InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		for (final UUID id : sessionIds) {
			executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						Session session = client.getSession(id);
						session.setName(session.getName() + "_modified");
						client.updateSession(session);
					} catch (RestException e) {
						e.printStackTrace();
					}
				}
			});
		}
		executor.shutdown();
		boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
		assertEquals(false, timeout);
	}

	@Test
	public void getDatasetsParallel() throws IOException, InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		for (final UUID id : datasetIds) {
			executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						client.getDataset(datasetSessionId, id);
					} catch (RestException e) {
						e.printStackTrace();
					}
				}
			});
		}
		executor.shutdown();
		boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
		assertEquals(false, timeout);
	}

	@Test
	public void putDatasetsParallel() throws IOException, InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		for (final UUID id : datasetIds) {
			executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						Dataset d = client.getDataset(datasetSessionId, id);
						d.setName(d.getName() + "_modified");
						client.updateDataset(datasetSessionId, d);
					} catch (RestException e) {
						e.printStackTrace();
					}
				}
			});
		}
		executor.shutdown();
		boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
		assertEquals(false, timeout);
	}

	@Test
	public void getJobsParallel() throws IOException, InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		for (final UUID id : jobIds) {
			executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						client.getJob(datasetSessionId, id);
					} catch (RestException e) {
						e.printStackTrace();
					}
				}
			});
		}
		executor.shutdown();
		boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
		assertEquals(false, timeout);
	}

	@Test
	public void putJobsParallel() throws IOException, InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		for (final UUID id : jobIds) {
			executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						Job j = client.getJob(datasetSessionId, id);
						j.setScreenOutput("" + j.getScreenOutput() + "output\n");
						client.updateJob(datasetSessionId, j);
					} catch (RestException e) {
						e.printStackTrace();
					}
				}
			});
		}
		executor.shutdown();
		boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
		assertEquals(false, timeout);
	}

	@Test
	public void getSessionDatasets() throws RestException {
		client.getDatasets(datasetSessionId);
	}

	@Test
	public void getSessionJobs() throws RestException {
		client.getJobs(datasetSessionId);
	}

	@Test
	public void getSessionsSerial() throws RestException {
		for (UUID id : sessionIds) {
			client.getSession(id);
		}
	}
}
