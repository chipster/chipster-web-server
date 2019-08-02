package fi.csc.chipster.sessionworker;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
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

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionState;

@Path("sessions")
public class SessionWorkerResource {
	
	private static Logger logger = LogManager.getLogger();
	
	private ServiceLocatorClient serviceLocator;

	private File tempDir;
	
	public SessionWorkerResource(ServiceLocatorClient serviceLocator) {
		this.serviceLocator = serviceLocator;
		
		// all files in this directory will be deleted
		tempDir = new File("tmp/session-worker");
		tempDir.mkdirs();
		
		// let's assumes all session workers have a private volume
		// delete temp files of previous run in case it's persistent
		for (File file : tempDir.listFiles()) {
			file.delete();
		}		
	}
	
    @GET
    @Path("{sessionId}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Transaction
    public Response get(@PathParam("sessionId") UUID sessionId, @Context SecurityContext sc) throws IOException, RestException {
    	    
    	StaticCredentials credentials = getUserCredentials(sc);
    	// we only allowed to get the public URI with client credentials
    	RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, credentials, Role.SERVER);
		SessionDbClient sessionDb = new SessionDbClient(serviceLocator, credentials, Role.CLIENT);
				
		ArrayList<InputStreamEntry> entries = new ArrayList<>();
		Session session = sessionDb.getSession(sessionId);
		
		JsonSession.packageSession(sessionDb, fileBroker, session, sessionId, entries);
		
		ResponseBuilder response = Response.ok(getZipStreamingOuput(entries));
		RestUtils.configureForDownload(response, session.getName()+ ".zip");
		return response.build();
    }

	/**
	 * Create a zip stream of the given input streams. Just like packaging multiple files to single zip file, but without
	 * requiring the inputs and outputs to be local files. This allows us to read the input files from the file-broker over
	 * HTTP, create the zip file on the fly and write it directly to the client.
	 * 
	 * @param entries Files to store in the zip file.
	 * 
	 * @return StreamingOutput of the created zip file.
	 */
	private StreamingOutput getZipStreamingOuput(ArrayList<InputStreamEntry> entries) {
		return new StreamingOutput() {
			
			@Override
			public void write(OutputStream output) throws IOException, WebApplicationException {
				// a decent output buffer seems to improve performance a bit (90MB/s -> 110MB/s)
			    try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(output, 2*1024*1024))) {
				    
				    for (InputStreamEntry entry : entries) {
				    	
				        zos.putNextEntry(new ZipEntry(entry.getName()));
				        zos.setLevel(entry.getCompressionLevel());
				        
				        try (InputStream entryStream = entry.getInputStreamCallable().call()) {			        	
				        	IOUtils.copy(entryStream, zos);
				        }
				        
				        zos.closeEntry();				
				    }
			    } catch (Exception e) {
			    	logger.error("error in copying dataset input stream to zip output stream", e);
			    	throw new InternalServerErrorException("error in copying dataset input stream to zip output stream");
			    }
			}
		};
	}   

	@POST
	@RolesAllowed({Role.CLIENT, Role.SERVER}) // don't allow SessionDbTokens
	@Produces(MediaType.APPLICATION_JSON)
	@Path("{sessionId}/datasets/{datasetId}")
	@Transaction
    public Response post(@PathParam("sessionId") UUID sessionId, @PathParam("datasetId") UUID zipDatasetId, @Context SecurityContext sc) throws RestException, IOException {
		
		// curl localhost:8009/sessions/8997e0d1-1c0a-4295-af3f-f191c96e589a/datasets/8997e0d1-1c0a-4295-af3f-f191c96e589a -I -X POST --user token:<TOKEN>
				
		StaticCredentials credentials = getUserCredentials(sc);
		
		// use client creds for the actual file operations (to check the access rights)
		RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, credentials, Role.SERVER);		
		SessionDbClient sessionDb = new SessionDbClient(serviceLocator, credentials, Role.SERVER);
		
		return Response.ok(new StreamingOutput() {
			
			private CountDownLatch latch = new CountDownLatch(1);
			
	        @Override
	        public void write(OutputStream output) throws IOException, WebApplicationException {
	        	
	        	ArrayList<String> warnings = new ArrayList<>();
	        	ArrayList<String> errors = new ArrayList<>();
	        	
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
	    		HashMap<String, ArrayList<String>> response = new HashMap<>();
	    		response.put("warnings", warnings);
	    		response.put("errors", errors);

	    		latch.countDown();
	    		output.write(RestUtils.asJson(response).getBytes());
	    		output.close();
	        }
        }).build();
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

	private StaticCredentials getUserCredentials(SecurityContext sc) {
		// use client's token to authenticate to sessionDb
		// alternatively we could check the authorization here, like file-broker
		String token = ((AuthPrincipal)sc.getUserPrincipal()).getTokenKey();
		return new StaticCredentials("token", token);
	}
}
