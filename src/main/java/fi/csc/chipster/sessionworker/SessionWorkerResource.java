package fi.csc.chipster.sessionworker;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

@Path("sessions")
public class SessionWorkerResource {
	
	private static Logger logger = LogManager.getLogger();
	
	private ServiceLocatorClient serviceLocator;
	
	public SessionWorkerResource(ServiceLocatorClient serviceLocator) {
		this.serviceLocator = serviceLocator;
	}
	
    @GET
    @Path("{sessionId}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Transaction
    public Response get(@PathParam("sessionId") UUID sessionId, @Context SecurityContext sc) throws IOException, RestException {
    	    
    	StaticCredentials credentials = getUserCredentials(sc);
    	RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, credentials, Role.SERVER);
		SessionDbClient sessionDb = new SessionDbClient(serviceLocator, credentials, Role.SERVER);
				
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
	@Produces(MediaType.APPLICATION_JSON)
	@Path("{sessionId}/datasets/{datasetId}")
	@Transaction
    public Response post(@PathParam("sessionId") UUID sessionId, @PathParam("datasetId") UUID zipDatasetId, @Context SecurityContext sc) throws RestException, IOException {
		
		// curl localhost:8009/sessions/8997e0d1-1c0a-4295-af3f-f191c96e589a/datasets/8997e0d1-1c0a-4295-af3f-f191c96e589a -I -X POST --user token:<TOKEN>
				
		StaticCredentials credentials = getUserCredentials(sc);
		
		//TODO we can get only the public address of the file-broker and session-db with client credentials, use server credentials to get the address, but client creds for the actual file operations (to check the access rights)
		RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, credentials, Role.CLIENT);		
		SessionDbClient sessionDb = new SessionDbClient(serviceLocator, credentials, Role.CLIENT);
		
		return Response.ok(new StreamingOutput() {
			
			private CountDownLatch latch = new CountDownLatch(1);
			
	        @Override
	        public void write(OutputStream output) throws IOException, WebApplicationException {
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
		    			sessionData = XmlSession.extractSession(fileBroker, sessionDb, sessionId, zipDatasetId);
		    		}
		    		
		    		if (sessionData == null) {
		    			throw new BadRequestException("unrecognized file format");
		    		}
		    		
		    		ArrayList<String> warnings = updateSession(sessionDb, sessionId, sessionData);
		    		
		    		HashMap<String, ArrayList<String>> response = new HashMap<>();
		    		response.put("warnings", warnings);
	
		    		latch.countDown();
		    		output.write(RestUtils.asJson(warnings).getBytes());
		    		output.close();
				} catch (RestException e) {
					latch.countDown();
					logger.error("session extraction failed", e);
				}
	        }
        }).build();
    }

	
	private ArrayList<String> updateSession(SessionDbClient sessionDb, UUID sessionId, ExtractedSession extractedSession) throws RestException {
		
		Session session = extractedSession.getSession();
		Collection<Dataset> datasets = session.getDatasets().values();
		Collection<Job> jobs = session.getJobs().values();
		HashMap<UUID, UUID> datasetIdMap = extractedSession.getDatasetIdMap();
		HashMap<UUID, UUID> jobIdMap = new HashMap<>();
		ArrayList<String> warnings = new ArrayList<String>();
		
		// update session object
		session.setSessionId(sessionId);
		sessionDb.updateSession(session);

		// create jobs objects
		for (Job job : jobs) {
			UUID oldId = job.getJobId();
			job.setJobId(null);
			// dataset ids have changed
			for (Input input : job.getInputs()) {
				UUID newDatasetId = datasetIdMap.get(UUID.fromString(input.getDatasetId()));
				if (input.getDatasetId() != null && newDatasetId == null) {					
					warnings.add("input dataset of job " + job.getToolId() + " " + input.getInputId() + " missing");
				} else {
					input.setDatasetId(newDatasetId.toString());
				}
			}
			UUID newId = sessionDb.createJob(sessionId, job);
			jobIdMap.put(oldId, newId);
		}

		// update dataset objects
		for (Dataset dataset: datasets) {
			UUID oldId = dataset.getDatasetId();
			UUID newId = datasetIdMap.get(oldId);
			if (newId == null) {
				warnings.add("dataset " + dataset.getName() + " missing");
			}
			dataset.setDatasetId(newId);
			UUID newSourceJobId = jobIdMap.get(dataset.getSourceJob());
			if (dataset.getSourceJob() != null && newSourceJobId == null) {
				//throw new BadRequestException("source job of dataset " + dataset.getName() + " missing");
				warnings.add("source job of dataset " + dataset.getName() + " missing");
			}
			// job ids have changed
			dataset.setSourceJob(newSourceJobId);
			// file-broker has set the File already in the upload
			dataset.setFile(null);
			
			System.out.println("updating dataset " + oldId + " " + newId);
			
			sessionDb.updateDataset(sessionId, dataset);
		}
		
		return warnings;
	}

	private StaticCredentials getUserCredentials(SecurityContext sc) {
		// use client's token to authenticate to sessionDb
		// alternatively we could check the authorization here, like file-broker
		String token = ((AuthPrincipal)sc.getUserPrincipal()).getTokenKey();
		return new StaticCredentials("token", token);
	}
}
