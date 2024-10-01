package fi.csc.chipster.sessionworker;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;

public class SessionWorkerClient {

	private static Logger logger = LogManager.getLogger();

	private WebTarget sessionWorkerTarget;
	private SessionDbClient sessionDbClient;
	private RestFileBrokerClient fileBrokerClient;

	public SessionWorkerClient(WebTarget sessionWorkerTarget1, SessionDbClient sessionDbClient1,
			RestFileBrokerClient fileBrokerClient1) {
		this.sessionWorkerTarget = sessionWorkerTarget1;
		this.sessionDbClient = sessionDbClient1;
		this.fileBrokerClient = fileBrokerClient1;
	}

	public UUID packageSessionToZip(UUID sessionId1) throws RestException {
		WebTarget target = sessionWorkerTarget.path("sessions").path(sessionId1.toString());
		Response response = target.request().post(null);

		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("packaging session zip failed", response, target.getUri());
		}

		@SuppressWarnings("unchecked")
		HashMap<String, Object> responseMap = response.readEntity(HashMap.class);

		@SuppressWarnings("unchecked")
		List<String> errors = (List<String>) responseMap.get("errors");

		if (!errors.isEmpty()) {
			throw new RestException("packaging session zip failed: " + RestUtils.asJson(errors));
		}

		return UUID.fromString((String) responseMap.get("datasetId"));
	}

	public UUID uploadZipSession(InputStream zipBytes, long length) throws RestException {

		Session session = new Session();

		UUID sessionId = sessionDbClient.createSession(session);

		return uploadZipSession(zipBytes, sessionId, length);
	}

	public UUID uploadZipSession(InputStream zipBytes, UUID sessionId, long length) throws RestException {

		Dataset zipDataset = new Dataset();

		// create a dataset for the zip upload
		UUID zipDatasetId = sessionDbClient.createDataset(sessionId, zipDataset);

		return uploadZipSession(zipBytes, sessionId, zipDatasetId, length);
	}

	public UUID uploadZipSession(InputStream zipBytes, UUID sessionId, UUID zipDatasetId, long length)
			throws RestException {

		// upload the zip
		fileBrokerClient.upload(sessionId, zipDatasetId, zipBytes, length);

		return extractZipSession(sessionId, zipDatasetId);
	}

	public UUID extractZipSession(UUID sessionId, UUID zipDatasetId) throws RestException {

		WebTarget target = sessionWorkerTarget
				.path("sessions").path(sessionId.toString())
				.path("datasets").path(zipDatasetId.toString());

		// extract the zip
		Response response = target.request().post(null, Response.class);

		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("session extraction failed", response, target.getUri());
		}

		@SuppressWarnings("unchecked")
		HashMap<String, Object> extractionResult = response.readEntity(HashMap.class);

		@SuppressWarnings("unchecked")
		List<String> errors = (List<String>) extractionResult.get("errors");
		@SuppressWarnings("unchecked")
		List<String> warnings = (List<String>) extractionResult.get("warnings");

		if (errors != null && !errors.isEmpty()) {
			throw new RestException("zip session extraction failed: " + RestUtils.asJson(errors));
		}

		if (warnings != null && !warnings.isEmpty()) {
			logger.warn("warnings in zip session extraction: " + RestUtils.asJson(warnings));
		}

		this.sessionDbClient.deleteDataset(sessionId, zipDatasetId);

		return sessionId;
	}
}
