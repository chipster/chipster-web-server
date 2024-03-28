package fi.csc.chipster.s3storage;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

public class S3Storage {

	private Logger logger = LogManager.getLogger();

	private AuthenticationClient authService;
	private ServiceLocatorClient serviceLocator;
	private Config config;
	@SuppressWarnings("unused")
	private String serviceId;

	private SessionDbClient sessionDbClient;

	private HttpServer adminServer;

	private S3StorageClient s3StorageClient;

	public S3Storage(Config config) {
		this.config = config;
	}

	/**
	 * Starts a HTTP server exposing the REST resources defined in this application.
	 * 
	 * @return
	 * @throws Exception
	 */
	public void startServer() throws Exception {

		String username = Role.S3_STORAGE;
		String password = config.getPassword(username);

		this.s3StorageClient = new S3StorageClient(config);

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());

		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());

		/*
		 * Listen for file deletions here. If the file-brokers
		 * would listen for these
		 * events, there might be many file-broker replicas, and all those would try to
		 * delete the file
		 * from the file-storage at the same time.
		 */
		sessionDbClient.subscribe(SessionDbTopicConfig.ALL_FILES_TOPIC, new SessionEventListener() {

			@Override
			public void onEvent(SessionEvent event) {

				logger.info("received a file event: " + event.getResourceType() + " " + event.getType());
				if (ResourceType.FILE == event.getResourceType()) {
					if (EventType.DELETE == event.getType()) {
						if (event.getResourceId() == null) {
							logger.warn("received a file deletion event with null id");
							return;
						}

						// we can't get the deleted file from the DB, so we have to get it from the
						// event
						if (event.getOldObject() == null) {
							logger.error("the File object in the event is null");
							return;
						}

						File file = RestUtils.parseJson(File.class, event.getOldObject());

						String storageId = file.getStorage();

						if (s3StorageClient.containsStorageId(storageId)) {

							logger.info(
									"delete file, storageId: " + storageId + ", fileId: " + event.getResourceId());

							s3StorageClient.delete(storageId, event.getResourceId());
						}
						// otherwise the file is on file-storage
					}
				}

			}

		}, "s3-storage-file-listener");

		this.adminServer = RestUtils.startAdminServer(Role.S3_STORAGE, config, authService,
				this.serviceLocator);

		logger.info("s3-storage started");
	}

	/**
	 * Main method.
	 * 
	 * @param args
	 * @throws Exception
	 * @throws InterruptedException s
	 */
	public static void main(String[] args) throws Exception {
		S3Storage s3Storage = new S3Storage(new Config());
		try {
			s3Storage.startServer();
		} catch (Exception e) {
			System.err.println("file-storage startup failed, exiting");
			e.printStackTrace(System.err);
			s3Storage.close();
			System.exit(1);
		}
	}

	public void close() {
		RestUtils.shutdown("s3-storage-admin", adminServer);

		try {
			if (sessionDbClient != null) {
				sessionDbClient.close();
			}
		} catch (IOException e) {
			logger.warn("failed to shutdown session-db client", e);
		}
	}
}
