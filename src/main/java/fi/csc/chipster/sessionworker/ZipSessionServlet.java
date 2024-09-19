package fi.csc.chipster.sessionworker;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.FileBrokerApi;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServletUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.MetadataFile;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionState;
import fi.csc.chipster.sessionworker.xml.XmlSession;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * Servlet for compressing and extracting session zip session files
 * 
 * This component can package a live server session to a zip file or extract a
 * zip file to a live server session. In both directions, this service can
 * handle zip files only when they are stored as a Dataset within the live
 * server session. This makes it easy for the client to upload and download the
 * zip files using the same APIs that it uses for individual files. Furthermore,
 * this allows us to know the file size when the browser starts the download,
 * avoiding the use of chunked encoding, which seems to be problematic in the
 * possible forward proxy.
 * 
 * The packaging and extraction methods send a simple json response when the
 * process is completed. Before that they send a stream of extra space
 * characters to prevent possible forward proxies (HAproxy in Openshift) from
 * closing the idle connection.
 * 
 * This is implemented as a servlet, because that allowed us to report errors
 * when we were still doing packaging on the fly and used chunked encoding.
 * Nowadays this could probably be a regualr Jersey endpoint as well. which
 * would parse the request path for us.
 * 
 * @author klemela
 *
 */
@Path("sessions")
public class ZipSessionServlet extends HttpServlet {

	private static final Object PATH_DATASETS = "datasets";

	public static final String TEMPORARY_ZIP_EXPORT = "temporary-zip-export";

	private static Logger logger = LogManager.getLogger();

	private ServiceLocatorClient serviceLocator;

	private File tempDir;

	private ExecutorService executor;

	public ZipSessionServlet(ServiceLocatorClient serviceLocator) {
		this.serviceLocator = serviceLocator;

		// all files in this directory will be deleted
		tempDir = new File("tmp/session-worker");
		tempDir.mkdirs();

		// let's assumes all session workers have a private volume
		// delete temp files of previous run in case it's persistent
		for (File file : tempDir.listFiles()) {
			file.delete();
		}

		this.executor = Executors.newCachedThreadPool();
	}

	private void packageSession(HttpServletResponse response, StaticCredentials credentials, UUID sessionId)
			throws IOException {

		// use client creds for the actual file operations (to check the access rights)
		// but use the server creds (stored in serviceLocator) to get the internal
		// addresses
		RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, credentials, Role.SERVER);
		SessionDbClient sessionDb = new SessionDbClient(serviceLocator, credentials, Role.SERVER);

		ArrayList<InputStreamEntry> entries = new ArrayList<>();

		try {
			Session session = sessionDb.getSession(sessionId);

			JsonSession.packageSession(sessionDb, fileBroker, session, sessionId, entries);

			response.setStatus(HttpServletResponse.SC_OK);

			ArrayList<String> errors = new ArrayList<>();

			/*
			 * File-broker will delete the file after it's downloaded once.
			 * 
			 * File-broker could just react on the TEMPORARY_ZIP_EXPORT, but architecturally
			 * it's cleaner if only session-worker depends on file-broker and not the other
			 * way round. Maybe this could be useful somewhere else too.
			 */
			MetadataFile delAfterMF = new MetadataFile();
			delAfterMF.setName(FileBrokerApi.MF_DELETE_AFTER_DOWNLOAD);

			/*
			 * Special tag for exactly this use, so that client knows it can safely
			 * delete any remainging files with this MetadataFile name.
			 */
			MetadataFile tempZipMF = new MetadataFile();
			tempZipMF.setName(TEMPORARY_ZIP_EXPORT);

			Dataset zipDataset = new Dataset();
			zipDataset.setName(session.getName() + ".zip");
			zipDataset.setMetadataFiles(List.of(tempZipMF, delAfterMF));
			UUID datasetId = sessionDb.createDataset(sessionId, zipDataset);

			OutputStream output2 = new PipedOutputStream();
			PipedInputStream in = new PipedInputStream((PipedOutputStream) output2);

			CountDownLatch latch = new CountDownLatch(1);

			OutputStream respoonseOutput = response.getOutputStream();

			keepAliveWithSpaces(respoonseOutput, latch);

			// start creating the zip stream in background thread (may complete before all
			// data is uploaded)
			executor.submit(() -> {
				try {
					streamZip(entries, output2);
				} catch (IOException e) {
					logger.error("failed to package zip session", e);
					errors.add("failed to package zip session: " + e.getMessage());
				}
			});

			// upload in the zip stream in this thread, so that we send response to this
			// servlet request only after the upload has really completed
			try {
				fileBroker.upload(sessionId, datasetId, in, null, true);

			} catch (RestException e) {
				logger.error("upload failed", e);
				errors.add("failed to package zip session: " + e.getMessage());
			}

			String datasetIdString = null;

			if (errors.isEmpty()) {
				// only return datasetId after success
				datasetIdString = datasetId.toString();
			} else {
				// something went wrong, delete the zip dataset
				logger.info("something went wrong, delete the incomplete zip dataset");
				try {
					sessionDb.deleteDataset(sessionId, datasetId);
				} catch (RestException e1) {
					logger.error("unable to clean up: " + e1);
				}
			}

			response.setContentType(MediaType.APPLICATION_JSON);

			HashMap<String, Object> json = new HashMap<>();
			json.put("datasetId", datasetIdString);
			json.put("errors", errors);

			logger.info("response: " + RestUtils.asJson(json, true));

			latch.countDown();
			respoonseOutput.write(RestUtils.asJson(json).getBytes());
			respoonseOutput.close();

		} catch (RestException e) {
			throw ServletUtils.extractRestException(e);
		}
	}

	/**
	 * Create a zip stream of the given input streams. Just like packaging multiple
	 * files to single zip file, but without
	 * requiring the inputs and outputs to be local files. This allows us to read
	 * the input files from the file-broker over
	 * HTTP, create the zip file on the fly and write it directly to the client.
	 * 
	 * @param entries Files to store in the zip file.
	 * 
	 * @return StreamingOutput of the created zip file.
	 * @throws IOException
	 */
	private void streamZip(ArrayList<InputStreamEntry> entries, OutputStream output) throws IOException {

		try {

			// a decent output buffer seems to improve performance a bit (90MB/s -> 110MB/s)
			ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(output, 2 * 1024 * 1024));

			for (InputStreamEntry entry : entries) {

				ZipEntry zipEntry = new ZipEntry(entry.getName());
				zos.putNextEntry(zipEntry);
				zos.setLevel(entry.getCompressionLevel());

				try (InputStream entryStream = entry.getInputStreamCallable().call()) {

					IOUtils.copy(entryStream, zos);
				}

				zos.closeEntry();
			}

			// close zip stream only when there was no errors
			zos.close();

		} catch (Exception e) {

			// error happened. Close the output stream without closing the zip stream so
			// that the file isn't a valid zip format

			output.close();
			throw new IOException("error in copying dataset input stream to zip output stream", e);
		}
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {

		// curl
		// localhost:8009/sessions/8997e0d1-1c0a-4295-af3f-f191c96e589a/datasets/8997e0d1-1c0a-4295-af3f-f191c96e589a
		// -I -X POST --user token:<TOKEN>

		StaticCredentials credentials = getUserCredentials(request);

		String pathInfo = request.getPathInfo();

		if (pathInfo == null) {
			throw new NotFoundException();
		}

		// parse path
		String[] path = pathInfo.split("/");

		if (path.length < 2) {
			throw new NotFoundException();
		}

		if (!"".equals(path[0])) {
			throw new BadRequestException("path doesn't start with slash");
		}

		UUID sessionId = UUID.fromString(path[1]);

		if (path.length == 2) {

			// curl
			// localhost:8009/sessions/8997e0d1-1c0a-4295-af3f-f191c96e589a -I -X POST
			// --user token:<TOKEN>

			this.packageSession(response, credentials, sessionId);

		} else {

			// curl
			// localhost:8009/sessions/8997e0d1-1c0a-4295-af3f-f191c96e589a/datasets/8997e0d1-1c0a-4295-af3f-f191c96e589a
			// -I -X POST --user token:<TOKEN>

			if (path.length != 4) {
				throw new NotFoundException();
			}

			if (!PATH_DATASETS.equals(path[2])) {
				throw new NotFoundException();
			}

			UUID zipDatasetId = UUID.fromString(path[3]);

			extractSession(response, credentials, sessionId, zipDatasetId);
		}
	}

	private void extractSession(HttpServletResponse response, StaticCredentials credentials, UUID sessionId,
			UUID zipDatasetId) throws IOException {

		// use client creds for the actual file operations (to check the access rights)
		RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, credentials, Role.SERVER);
		SessionDbClient sessionDb = new SessionDbClient(serviceLocator, credentials, Role.SERVER);

		// check that user has read-write access to this session to respond
		// with correct status code
		try {
			sessionDb.getDataset(sessionId, zipDatasetId, true);
		} catch (RestException e) {
			throw ServletUtils.extractRestException(e);
		}

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(MediaType.APPLICATION_JSON);

		OutputStream output = response.getOutputStream();

		ArrayList<String> warnings = new ArrayList<>();
		ArrayList<String> errors = new ArrayList<>();

		CountDownLatch latch = new CountDownLatch(1);

		try {

			keepAliveWithSpaces(output, latch);

			ExtractedSession sessionData = JsonSession.extractSession(fileBroker, sessionDb, sessionId, zipDatasetId);

			if (sessionData == null) {
				sessionData = XmlSession.extractSession(fileBroker, sessionDb, sessionId, zipDatasetId, tempDir);
			}

			if (sessionData == null) {
				throw new BadRequestException("unrecognized file format");
			}

			// extraction warnings
			warnings.addAll(sessionData.getWarnings());
			errors.addAll(sessionData.getErrors());

			warnings.addAll(updateSession(sessionDb, sessionId, sessionData));

		} catch (RestException e) {
			errors.add("session extraction failed: " + e.getMessage());
			logger.warn("session extraction failed", e);

		} catch (Exception e) {
			if (ExceptionUtils.getRootCause(e) instanceof ZipException) {
				errors.add("session extraction failed: " + e.getMessage());
				logger.warn("session extraction failed", e);
			} else {
				errors.add("internal server error");
				logger.error("session extraction failed", e);
			}
		}

		// send errors in the body because HTTP 200 OK was already sent in the beginning
		// of the response
		HashMap<String, ArrayList<String>> json = new HashMap<>();
		json.put("warnings", warnings);
		json.put("errors", errors);

		latch.countDown();
		output.write(RestUtils.asJson(json).getBytes());
		output.close();

	}

	private void keepAliveWithSpaces(OutputStream output, CountDownLatch latch) {
		// keep sending 1k bytes every second to keep the connection open
		// jersey will buffer for 8kB and the OpenShift router expects to get the first
		// bytes in 30 seconds
		this.executor.submit(() -> {

			try {
				// respond with space characters that will be ignored in json deserialization
				String spaces = " ";

				for (int i = 0; i < 10; i++) {
					spaces = spaces + spaces;
				}

				spaces += "\n";

				while (!latch.await(1, TimeUnit.SECONDS)) {
					output.write(spaces.getBytes());
					output.flush();
				}
			} catch (InterruptedException | IOException e) {
				logger.error("error in keep-alive thread", e);
			}
		});
	}

	private StaticCredentials getUserCredentials(HttpServletRequest request) {
		// use client's token to authenticate to sessionDb
		// alternatively we could check the authorization here, like file-broker
		String token = ServletUtils.getToken(request);
		return new StaticCredentials("token", token);
	}

	private ArrayList<String> updateSession(SessionDbClient sessionDb, UUID sessionId,
			ExtractedSession extractedSession)
			throws RestException, JsonParseException, JsonMappingException, IOException {

		Session session = extractedSession.getSession();
		Collection<Dataset> datasets = extractedSession.getDatasetMap().values();
		Collection<Job> jobs = extractedSession.getJobMap().values();

		Set<UUID> datasetIds = datasets.stream().map(d -> d.getDatasetId()).collect(Collectors.toSet());
		Set<UUID> jobIds = jobs.stream().map(j -> j.getJobId()).collect(Collectors.toSet());

		ArrayList<String> warnings = new ArrayList<String>();

		// check input references
		for (Job job : jobs) {
			Iterator<Input> inputIter = job.getInputs().iterator();
			while (inputIter.hasNext()) {
				Input input = inputIter.next();
				String datasetId = input.getDatasetId();
				if (datasetId != null && !datasetIds.contains(UUID.fromString(datasetId))) {
					warnings.add("job '" + job.getToolId() + "' has input '" + input.getInputId()
							+ "' but the dataset is no more in the session");
					// the server doesn't allow jobs with invalid inputs
					inputIter.remove();
				}
			}
		}

		for (Job job : jobs) {
			job.setJobIdPair(sessionId, job.getJobId());
		}

		sessionDb.createJobs(sessionId, new ArrayList<Job>(jobs));

		// check source job references
		for (Dataset dataset : datasets) {
			UUID sourceJobId = dataset.getSourceJob();
			if (sourceJobId != null && !jobIds.contains(sourceJobId)) {
				warnings.add("source job of dataset " + dataset.getName() + " missing");
			}
		}

		for (Dataset dataset : datasets) {
			dataset.setDatasetIdPair(sessionId, dataset.getDatasetId());
			// file-broker has set the File already in the upload
			dataset.setFile(null);
		}

		sessionDb.updateDatasets(sessionId, new ArrayList<Dataset>(datasets));

		// update session object
		session.setSessionId(sessionId);
		session.setState(SessionState.READY);
		sessionDb.updateSession(session);

		return warnings;
	}
}
