package fi.csc.chipster.filebroker;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.client.ClientProperties;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;

public class RestFileBrokerClient {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private ServiceLocatorClient serviceLocator;
	private List<String> fileBrokerList;
	private CredentialsProvider credentials;

	private WebTarget fileBrokerTarget;

	public RestFileBrokerClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials) {
		this.serviceLocator = serviceLocator;
		this.credentials = credentials;
		
		this.fileBrokerList = serviceLocator.get(Role.FILE_BROKER);
	
		if (fileBrokerList.isEmpty()) {
			throw new InternalServerErrorException("no session-dbs registered to service-locator");
		}
		
		// just take the first one for now
		init(fileBrokerList.get(0));
	}	
	
	public RestFileBrokerClient(String fileBrokerUri, CredentialsProvider credentials) {		
		this.credentials = credentials;
		init(fileBrokerUri);		
	}
	
	private void init(String sessionDbUri) {

		if (credentials != null) {
			fileBrokerTarget = AuthenticationClient.getClient(credentials.getUsername(), credentials.getPassword(), true).target(sessionDbUri);
		} else {
			// for testing
			fileBrokerTarget = AuthenticationClient.getClient().target(sessionDbUri);
		}
	}
	
	
	// targets
	
	private WebTarget getDatasetTarget(UUID sessionId, UUID datasetId) {
		return fileBrokerTarget.path("sessions").path(sessionId.toString()).path("datasets").path(datasetId.toString());
	}
	
	// methods 
	
	public void upload(UUID sessionId, UUID datasetId, File file) throws RestException, IOException {

		try (FileInputStream in = new FileInputStream(file)) {
			upload(sessionId, datasetId, in);
		}
	}
	
	public void upload(UUID sessionId, UUID datasetId, InputStream inputStream) throws RestException {
		WebTarget target = getDatasetTarget(sessionId, datasetId);
		
		System.out.println(target.getUri());

		// Use chunked encoding to disable buffering. HttpUrlConnector in 
		// Jersey buffers the whole file before sending it by default, which 
		// won't work with big files.
		target.property(ClientProperties .REQUEST_ENTITY_PROCESSING, "CHUNKED");
		Response response = target.request().put(Entity.entity(inputStream, MediaType.APPLICATION_OCTET_STREAM), Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("upload failed ", response, target.getUri());
		}
	}
	
	public void download(UUID sessionId, UUID datasetId, File destFile) throws RestException, IOException {
		
		try (InputStream inStream = download(sessionId, datasetId)) {
			Files.copy(inStream, destFile.toPath());
		}
	}
		
	
	public InputStream download(UUID sessionId, UUID datasetId) throws RestException {
		WebTarget target = getDatasetTarget(sessionId, datasetId);
		Response response = target.request().get(Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("getting input stream failed", response, target.getUri());
		}
		return response.readEntity(InputStream.class);
	}	
}
