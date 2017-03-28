package fi.csc.chipster.filebroker;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;
import org.glassfish.jersey.client.ClientProperties;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.RestPhenodataUtils;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.microarray.client.session.SessionManager;
import fi.csc.microarray.filebroker.ChecksumException;
import fi.csc.microarray.filebroker.ChecksumInputStream;
import fi.csc.microarray.filebroker.DbSession;
import fi.csc.microarray.filebroker.FileBrokerClient;
import fi.csc.microarray.filebroker.FileBrokerException;
import fi.csc.microarray.messaging.admin.StorageAdminAPI.StorageEntryMessageListener;
import fi.csc.microarray.util.Exceptions;
import fi.csc.microarray.util.IOUtils;
import fi.csc.microarray.util.IOUtils.CopyProgressListener;

/**
 * RestFileBrokerClient is a bridge between the old code written for JMS and the
 * new REST style web backend. It uses new session-db and file-broker servers,
 * but implements the old FileBrokerClient interface, which makes it more or
 * less compatible with the old Swing GUI and the old comp server.
 * 
 * @author klemela
 */
public class LegacyRestFileBrokerClient implements FileBrokerClient {
	
	@SuppressWarnings("unused")
	private static final Logger logger = Logger.getLogger(LegacyRestFileBrokerClient.class);
		
	private SessionDbClient sessionDbClient;
	private WebTarget fileBrokerTarget;
	private SessionManager sessionManager;

	public LegacyRestFileBrokerClient(SessionDbClient sessionDbClient2, String restProxy, AuthenticationClient authClient, SessionManager sessionManager) {
		this.sessionDbClient = sessionDbClient2;
		this.fileBrokerTarget = authClient.getAuthenticatedClient().target("http://" + restProxy + "/filebroker/");
		this.sessionManager = sessionManager;
	}

	public LegacyRestFileBrokerClient(SessionDbClient sessionDbClient2, ServiceLocatorClient serviceLocator, AuthenticationClient authClient) {
		this.sessionDbClient = sessionDbClient2;
		this.fileBrokerTarget = authClient.getAuthenticatedClient().target(serviceLocator.get(Role.FILE_BROKER).get(0)); 
	}

	@Override
	public String addFile(UUID jobId, UUID sessionId, FileBrokerArea area, File file, CopyProgressListener progressListener, String datsetName) throws FileBrokerException, IOException {
		
		if (RestPhenodataUtils.FILE_PHENODATA.equals(file.getName()) || RestPhenodataUtils.FILE_PHENODATA2.equals(file.getName())) {
			// phenodata is stored now in the dataset properties, so the phenodata files can be skipped 
			return null;
		}
		
		Dataset dataset = new Dataset();
		dataset.setSourceJob(jobId);
		dataset.setName(datsetName);
		dataset.setMetadata(RestPhenodataUtils.parseMetadata(file.getParentFile(), file.getName()));
		UUID datasetId;
		try {
			datasetId = sessionDbClient.createDataset(sessionId, dataset);
			InputStream inputStream = new FileInputStream(file);
			upload(sessionId, datasetId.toString(), inputStream);
			logger.info("uploaded dataset " + datasetId.toString());
		} catch (RestException e) {
			throw new FileBrokerException("failed to create a result dataset", e);
		}
		
		return datasetId.toString();
	}
	
	private void upload(UUID sessionId, String dataId, InputStream inputStream) throws IOException {
		String datasetPath = "sessions/" + sessionId.toString() + "/datasets/" + dataId;
		WebTarget datasetTarget = fileBrokerTarget.path(datasetPath);

		// Use chunked encoding to disable buffering. HttpUrlConnector in 
		// Jersey buffers the whole file before sending it by default, which 
		// won't work with big files.
		fileBrokerTarget.property(ClientProperties .REQUEST_ENTITY_PROCESSING, "CHUNKED");
		Response response = datasetTarget.request().put(Entity.entity(inputStream, MediaType.APPLICATION_OCTET_STREAM), Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new IOException("upload failed: " + response.getStatus() + " " + response.readEntity(String.class) + " " + datasetTarget.getUri());
		}
	}

	private UUID getSessionId() {
		return sessionManager.getSessionId();
	}

	/**
	 * @return md5 String of the uploaded data, if enabled in configuration
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#addFile(InputStream, CopyProgressListener)
	 */
	@Override
	public String addFile(String dataId, FileBrokerArea area, InputStream file, long contentLength, CopyProgressListener progressListener) throws FileBrokerException, IOException {
		
		upload(getSessionId(), dataId, file);
		return null;
	}
	
	@Override
	public ChecksumInputStream getInputStream(String dataId) throws IOException, FileBrokerException {
	
		InputStream remoteStream = download(getSessionId(), dataId);
		return new ChecksumInputStream(remoteStream, true);					
	}

	
	private InputStream download(UUID sessionId, String dataId) throws FileBrokerException {
		String datasetPath = "sessions/" + sessionId.toString() + "/datasets/" + dataId;
		WebTarget datasetTarget = fileBrokerTarget.path(datasetPath);
		Response response = datasetTarget.request().get(Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new FileBrokerException("get input stream failed: " + response.getStatus() + " " + response.readEntity(String.class) + " " + datasetTarget.getUri());
		}
		return response.readEntity(InputStream.class);
	}

	/**
	 * Get a local copy of a file. If the dataId  matches any of the files found from 
	 * local filebroker paths (given in constructor of this class), then it is symlinked or copied locally.
	 * Otherwise the file pointed by the dataId is downloaded.
	 * @throws JMSException 
	 * @throws ChecksumException 
	 * 
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#getFile(File, URL)
	 */
	@Override
	public void getFile(UUID sessionId, String dataId, File destFile) throws IOException, FileBrokerException, ChecksumException {
		
		InputStream inStream = download(sessionId, dataId);
		IOUtils.copy(inStream, destFile);		
	}

	@Override
	public boolean isAvailable(String dataId, Long contentLength, String checksum, FileBrokerArea area) throws FileBrokerException {
		
		try {
			InputStream inStream = download(getSessionId(), dataId);
			IOUtils.closeIfPossible(inStream);
			return true;
		} catch (NotFoundException e) {
			return false;
		}
	}

	
	@Override
	public boolean moveFromCacheToStorage(String dataId) throws FileBrokerException, FileBrokerException {
		return true;
	}
	
	/**
	 * @see fi.csc.microarray.filebroker.FileBrokerClient#getPublicFiles()
	 */
	@Override
	public List<URL> getPublicFiles() throws FileBrokerException {
		return fetchPublicFiles();
	}

	private List<URL> fetchPublicFiles() throws FileBrokerException {
		return new ArrayList<URL>();
	}

	@Override
	public boolean requestDiskSpace(long size) throws FileBrokerException {
		return true;
	}

	@Override
	public void saveRemoteSession(String sessionName, String sessionId, LinkedList<String> dataIds) throws FileBrokerException {
	}

	@Override
	public List<DbSession> listRemoteSessions() throws FileBrokerException {
		
		try {
			HashMap<UUID, fi.csc.chipster.sessiondb.model.Session> sessions;
			sessions = sessionDbClient.getSessions();

			List<DbSession> dbSessions = new LinkedList<>();
			for (fi.csc.chipster.sessiondb.model.Session session : sessions.values()) {
				dbSessions.add(new DbSession(session.getSessionId().toString(), session.getName(), null));
			}

			return dbSessions;
		} catch (RestException e) {
			throw new FileBrokerException(Exceptions.getStackTrace(e));
		}
	}
	
	@Override
	public StorageEntryMessageListener getStorageUsage() throws FileBrokerException, InterruptedException {
		return null;
	}

	@Override
	public List<DbSession> listPublicRemoteSessions() throws FileBrokerException {
		return new LinkedList<>();
	}

	@Override
	public void removeRemoteSession(String dataId) throws FileBrokerException {
	}

	@Override
	public String getExternalURL(String dataId) throws FileBrokerException, MalformedURLException {
		return null;
	}

	@Override
	public Long getContentLength(String dataId) throws IOException, FileBrokerException {
		return null;
	}
}
