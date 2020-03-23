package fi.csc.chipster.filebroker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.glassfish.jersey.client.ClientProperties;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.FileBrokerException;
import fi.csc.chipster.comp.PhenodataUtils;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.MetadataFile;
import fi.csc.chipster.util.IOUtils;

/**
 * RestFileBrokerClient is a bridge between the old code written for JMS and the
 * new REST style web backend. It uses new session-db and file-broker servers,
 * but implements the old FileBrokerClient interface, which makes it more or
 * less compatible with the old Swing GUI and the old comp server.
 * 
 * @author klemela
 */
public class LegacyRestFileBrokerClient {

	private static final long PHENODATA_FILE_MAX_SIZE = FileUtils.ONE_MB;
	private static final Logger logger = Logger.getLogger(LegacyRestFileBrokerClient.class);

	private SessionDbClient sessionDbClient;
//	private SessionManager sessionManager;
	private AuthenticationClient authClient;

	private ServiceLocatorClient serviceLocator;

	public LegacyRestFileBrokerClient(SessionDbClient sessionDbClient2, ServiceLocatorClient serviceLocator,
			AuthenticationClient authClient) {
		this.sessionDbClient = sessionDbClient2;
		this.authClient = authClient;
		this.serviceLocator = serviceLocator;
	}

	public String addFile(UUID jobId, UUID sessionId, File file, String datsetName, boolean isMetaOutput, File phenodataFile)
			throws IOException, FileBrokerException {

		if (!file.exists()) {
			throw new FileNotFoundException(file.getPath());
		}

		// phenodata files not added as datasets
		if (isMetaOutput) {
			return null;
		}

		// read phenodata file
		List<MetadataFile> metadataFiles = new ArrayList<>();
		if (phenodataFile != null) {
			if (phenodataFile.length() > PHENODATA_FILE_MAX_SIZE) {
				throw new RuntimeException("Phenodata file size too large: " + phenodataFile.getName() + " "
						+ FileUtils.byteCountToDisplaySize(phenodataFile.length()) + " bytes, limit is "
						+ FileUtils.byteCountToDisplaySize(PHENODATA_FILE_MAX_SIZE) + " bytes");
			}

			String phenodata = PhenodataUtils.processPhenodata(phenodataFile.toPath());
			metadataFiles.add(new MetadataFile("phenodata.tsv", phenodata));
		}

		Dataset dataset = new Dataset();
		dataset.setSourceJob(jobId);
		dataset.setName(datsetName);
		dataset.setMetadataFiles(metadataFiles);

		UUID datasetId;
		try {
			datasetId = sessionDbClient.createDataset(sessionId, dataset);
			InputStream inputStream = new FileInputStream(file);
			upload(sessionId, datasetId.toString(), inputStream, file.length());
			logger.info("uploaded dataset " + datasetId.toString());
		} catch (RestException e) {
			throw new FileBrokerException("failed to create a result dataset", e);
		}

		return datasetId.toString();
	}

	private void upload(UUID sessionId, String dataId, InputStream inputStream, long size) throws IOException {
		String datasetPath = "sessions/" + sessionId.toString() + "/datasets/" + dataId;
		WebTarget datasetTarget = getFileBrokerTarget().path(datasetPath);

		// Use chunked encoding to disable buffering. HttpUrlConnector in
		// Jersey buffers the whole file before sending it by default, which
		// won't work with big files.
		datasetTarget.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		Response response = datasetTarget.request()
				.header(FileBrokerResource.FLOW_TOTAL_SIZE, size)
				.put(Entity.entity(inputStream, MediaType.APPLICATION_OCTET_STREAM), Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			logger.warn("upload failed: " + response.getStatus() + " " + response.readEntity(String.class) + " "
					+ datasetTarget.getUri());
			throw new IOException("upload failed: " + response.getStatus() + " " + response.readEntity(String.class)
					+ " " + datasetTarget.getUri());
		}
	}

	private InputStream download(UUID sessionId, String dataId) throws FileBrokerException {
		String datasetPath = "sessions/" + sessionId.toString() + "/datasets/" + dataId;
		WebTarget datasetTarget = getFileBrokerTarget().path(datasetPath);
		Response response = datasetTarget.request().get(Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new FileBrokerException("get input stream failed: " + response.getStatus() + " "
					+ response.readEntity(String.class) + " " + datasetTarget.getUri());
		}
		return response.readEntity(InputStream.class);
	}

	/**
	 * Get a local copy of a file. If the dataId matches any of the files found from
	 * local filebroker paths (given in constructor of this class), then it is
	 * symlinked or copied locally. Otherwise the file pointed by the dataId is
	 * downloaded.
	 * 
	 * @throws JMSException
	 * 
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#getFile(File, URL)
	 */
	public void getFile(UUID sessionId, String dataId, File destFile)
			throws IOException, FileBrokerException {

		InputStream inStream = download(sessionId, dataId);
		IOUtils.copy(inStream, destFile);
	}

	private WebTarget getFileBrokerTarget() {
		return authClient.getAuthenticatedClient().target(serviceLocator.getInternalService(Role.FILE_BROKER).getUri());
	}
}
