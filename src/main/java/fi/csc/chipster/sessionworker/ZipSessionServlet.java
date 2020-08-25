package fi.csc.chipster.sessionworker;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.ParsedToken;
import fi.csc.chipster.auth.model.Role;
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
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionState;
import fi.csc.chipster.sessionworker.xml.XmlSession;

/**
 * Servlet for compressing and extracting session zip files
 * 
 * This is implemented as a servlet to show error in the browser when 
 * an exception during the download. I wasn't able to create the error in Jersey 
 * and others have had the same problem too: https://github.com/eclipse-ee4j/jersey/issues/3850.
 * 
 * It is important to show this error for the user, because otherwise the user might
 * think that he/she has a complete copy of the session when only part of the
 * files were copied. The browser does the downloading, so we don't have any 
 * way in the client side to monitor its progress. We could implement a new REST
 * endpoint, where from the javasscript could follow the progress of the download,
 * but handling that state information would be a lot of work, when several 
 * session-workers are running behind a load balancer.  
 *    
 * Simply throwing an IOException from the ServletOutputStream seems to be enough 
 * in servlet. However, there is a bit more work with parsing the path.
 * 
 * @author klemela
 *
 */
@Path("sessions")
public class ZipSessionServlet extends HttpServlet {
	
	private static final Object PATH_DATASETS = "datasets";

	private static Logger logger = LogManager.getLogger();
	
	private ServiceLocatorClient serviceLocator;

	private File tempDir;

	private AuthenticationClient authService;

	public ZipSessionServlet(ServiceLocatorClient serviceLocator, AuthenticationClient authService) {
		this.serviceLocator = serviceLocator;
		this.authService = authService;
		
		// all files in this directory will be deleted
		tempDir = new File("tmp/session-worker");
		tempDir.mkdirs();
		
		// let's assumes all session workers have a private volume
		// delete temp files of previous run in case it's persistent
		for (File file : tempDir.listFiles()) {
			file.delete();
		}		
	}
		
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {  
    	
    	StaticCredentials credentials = getUserCredentials(request);
    	
    	String pathInfo = request.getPathInfo();
    	
    	if (pathInfo == null) {
    		throw new NotFoundException();
    	}
    			
		// parse path
		String[] path = pathInfo.split("/");

		if (path.length != 2) {
			throw new NotFoundException();
		}

		if (!"".equals(path[0])) {
			throw new BadRequestException("path doesn't start with slash");
		}		

		UUID sessionId = UUID.fromString(path[1]);
	
    	    
    	// we only allowed to get the public URI with client credentials
    	RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, credentials, Role.SERVER);
		SessionDbClient sessionDb = new SessionDbClient(serviceLocator, credentials, Role.CLIENT);
				
		ArrayList<InputStreamEntry> entries = new ArrayList<>();
		
		try {
			Session session = sessionDb.getSession(sessionId);
		
			JsonSession.packageSession(sessionDb, fileBroker, session, sessionId, entries);
			
	        response.setStatus(HttpServletResponse.SC_OK);
	        response.setContentType(MediaType.APPLICATION_OCTET_STREAM);
//	        response.setHeader("Transfer-Encoding", "chunked");
//	        response.setCharacterEncoding("utf-8");
	//        response.setHeader("Cache-Control", "no-store");
	        
	        RestUtils.configureForDownload(response, session.getName()+ ".zip");
	        
	        OutputStream output = response.getOutputStream();
	
			streamZip(entries, output);
			
		} catch (RestException e) {
			throw ServletUtils.extractRestException(e);
		}
    }	

	/**
	 * Create a zip stream of the given input streams. Just like packaging multiple files to single zip file, but without
	 * requiring the inputs and outputs to be local files. This allows us to read the input files from the file-broker over
	 * HTTP, create the zip file on the fly and write it directly to the client.
	 * 
	 * @param entries Files to store in the zip file.
	 * 
	 * @return StreamingOutput of the created zip file.
	 * @throws IOException 
	 */
	private void streamZip(ArrayList<InputStreamEntry> entries, OutputStream output) throws IOException {
		
		// a decent output buffer seems to improve performance a bit (90MB/s -> 110MB/s)
		long totalBytes = 0;
	    try {
	    	
	    	ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(output, 2*1024*1024));
	    		
		    for (InputStreamEntry entry : entries) {
		    	
		    	System.out.println("add entry " + entry.getName());
		    	
		        zos.putNextEntry(new ZipEntry(entry.getName()));
		        zos.setLevel(entry.getCompressionLevel());
		        				        
	        	InputStream entryStream = entry.getInputStreamCallable().call();			        	
	        	int bytes = IOUtils.copy(entryStream, zos);
	        	entryStream.close();				        
		        
		        zos.closeEntry();
//		        zos.flush();
		        
		        totalBytes += bytes;
		    }
		    
		    // close zip stream only when there was no errors
		    zos.close();
		    
	    } catch (Exception e) {
	    			    	
	    	// error happened. Do not close zip so at least the file isn't a valid zip format
	    	logger.error("error in copying dataset input stream to zip output stream", e);
	    	
	    	System.out.println("bytes written " + totalBytes);
	    	
	    	/* show error in the browser's download
	    	 *  
	    	 * "Network error" in Chrome,
	    	 * "cannot parse response" in Safari,
	    	 * "source file could not be read" in Firefox
	    	 */
	    	throw new IOException("error in copying dataset input stream to zip output stream");
	    }
	}
	
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
	
//	@POST
//	@RolesAllowed({Role.CLIENT, Role.SERVER}) // don't allow SessionDbTokens
//	@Produces(MediaType.APPLICATION_JSON)
//	@Path("{sessionId}/datasets/{datasetId}")
//	@Transaction
//    public Response post(@PathParam("sessionId") UUID sessionId, @PathParam("datasetId") UUID zipDatasetId, @Context SecurityContext sc) throws RestException, IOException {
//		
		// curl localhost:8009/sessions/8997e0d1-1c0a-4295-af3f-f191c96e589a/datasets/8997e0d1-1c0a-4295-af3f-f191c96e589a -I -X POST --user token:<TOKEN>
				
		StaticCredentials credentials = getUserCredentials(request);
				
		// don't allow SessionDbTokens
		ParsedToken parsedToken = this.authService.validate(credentials.getPassword());
		rolesAllowed(parsedToken, Role.CLIENT, Role.SERVER);
		
		String pathInfo = request.getPathInfo();
		
    	if (pathInfo == null) {
    		throw new NotFoundException();
    	}
		
		// parse path
		String[] path = pathInfo.split("/");

		if (path.length != 4) {
			throw new NotFoundException();
		}

		if (!"".equals(path[0])) {
			throw new BadRequestException("path doesn't start with slash");
		}
		
		UUID sessionId = UUID.fromString(path[1]);
		
		if (!PATH_DATASETS.equals(path[2])) {
			throw new NotFoundException();
		}		

		UUID zipDatasetId = UUID.fromString(path[3]);		
		
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
    		// keep sending 1k bytes every second to keep the connection open
    		// jersey will buffer for 8kB and the OpenShift router expects to get the first bytes in 30 seconds
    		new Thread(new Runnable() {						
				@Override
				public void run() {
					try {
						// respond with space characters that will be ignored in json deserialization
						String spaces = " ";
						
						for (int i = 0; i < 10; i++) {
							spaces = spaces + spaces;
						}
						
						spaces += "\n";
						
						while(!latch.await(1, TimeUnit.SECONDS)) {
							output.write(spaces.getBytes());
							output.flush();
						}
					} catch (InterruptedException | IOException e) {
						logger.error("error in keep-alive thread", e);
					}
				}
			}).start();
    		
    		ExtractedSession sessionData = JsonSession.extractSession(fileBroker, sessionDb, sessionId, zipDatasetId);
		
    		if (sessionData == null) {
    			sessionData = XmlSession.extractSession(fileBroker, sessionDb, sessionId, zipDatasetId, tempDir);
    		}
    		
    		if (sessionData == null) {
    			throw new BadRequestException("unrecognized file format");
    		}
    		
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
    	
    	// send errors in the body because HTTP 200 OK was already sent in the beginning of the response
		HashMap<String, ArrayList<String>> json = new HashMap<>();
		json.put("warnings", warnings);
		json.put("errors", errors);

		latch.countDown();
		output.write(RestUtils.asJson(json).getBytes());
		output.close();
	        
    }

	
	private void rolesAllowed(ParsedToken parsedToken, String... roles) throws NotAuthorizedException {

		for (String allowedRole : Arrays.asList(roles)) {
			if (parsedToken.getRoles().contains(allowedRole)) {
				// suitable role found;
				return;
			}
		}
		
		throw new NotAuthorizedException("wrong role");
	}

	private StaticCredentials getUserCredentials(HttpServletRequest request) {
    	// use client's token to authenticate to sessionDb
		// alternatively we could check the authorization here, like file-broker
		String token = ServletUtils.getToken(request);
    	return new StaticCredentials("token", token);
	}

	private ArrayList<String> updateSession(SessionDbClient sessionDb, UUID sessionId, ExtractedSession extractedSession) throws RestException, JsonParseException, JsonMappingException, IOException {
		
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
					warnings.add("job '" + job.getToolId() + "' has input '" + input.getInputId() + "' but the dataset is no more in the session");
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
		for (Dataset dataset: datasets) {
			UUID sourceJobId = dataset.getSourceJob();
			if (sourceJobId != null && !jobIds.contains(sourceJobId)) {
				warnings.add("source job of dataset " + dataset.getName() + " missing");
			}
		}
		
		for (Dataset dataset: datasets) {
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
