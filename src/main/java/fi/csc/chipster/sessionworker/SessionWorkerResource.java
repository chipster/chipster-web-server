package fi.csc.chipster.sessionworker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
	
	private static final String SESSION_JSON = "session.json";
	private static final String DATASETS_JSON = "datasets.json";
	private static final String JOBS_JSON = "jobs.json";
	
    // download speed from a localhost server (NGS session where all the big files are already compressed)
    // DEFAULT: 		30s, 19MB/s
    // BEST_SPEED:		29s, 19MB/s
    // NO_COMPRESSION:	9s, 64MB/s			     
	protected static final int COMPRESSION_LEVEL = Deflater.NO_COMPRESSION;
	
	private static Logger logger = LogManager.getLogger();
	private ServiceLocatorClient serviceLocator;
	
    @GET
    @Path("{sessionId}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Transaction
    public Response get(@PathParam("sessionId") UUID sessionId, @Context SecurityContext sc) throws IOException, RestException {
    	    
    	StaticCredentials credentials = getUserCredentials(sc);
    	RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, credentials);
		SessionDbClient sessionDb = new SessionDbClient(serviceLocator, credentials);
		
		HashMap<String, Callable<InputStream>> entries = new HashMap<>();
		
		Session session = sessionDb.getSession(sessionId);
		
		String sessionJson = RestUtils.asJson(session, true);
		String datasetsJson = RestUtils.asJson(sessionDb.getDatasets(sessionId).values(), true);
		String jobsJson = RestUtils.asJson(sessionDb.getJobs(sessionId).values(), true);
		
		entries.put(SESSION_JSON, toCallable(sessionJson));
		entries.put(DATASETS_JSON, toCallable(datasetsJson));
		entries.put(JOBS_JSON, toCallable(jobsJson));
		
		for (Dataset dataset : sessionDb.getSession(sessionId).getDatasets().values()) {
			entries.put(dataset.getDatasetId().toString(), new Callable<InputStream>() {
				@Override
				public InputStream call() throws Exception {
					return fileBroker.download(sessionId, dataset.getDatasetId());
				}
			});
		}
		
		ResponseBuilder response = Response.ok(getZipStreamingOuput(entries));
		RestUtils.configureForDownload(response, session.getName()+ ".zip");
		return response.build();
    }

	/**
	 * Create a zip stream of the given input streams. Just like packaging multiple files to single zip file, but without
	 * requiring the inputs and outputs to be local files. This allows us to read the input files from the file-broker over
	 * HTTP, create the zip file on the fly and write it directly to the client.
	 * 
	 * @param entries Files to store in the zip file. Map key is the file name and the map value is a Callable<InputStream>, which 
	 * returns the file body as an InputStream.
	 * 
	 * @return StreamingOutput of the created zip file.
	 */
	private StreamingOutput getZipStreamingOuput(HashMap<String, Callable<InputStream>> entries) {
		return new StreamingOutput() {
			@Override
			public void write(OutputStream output) throws IOException, WebApplicationException {
			    try (ZipOutputStream zos = new ZipOutputStream(output)) {
				    zos.setLevel(COMPRESSION_LEVEL);
				    
				    for (Entry<String, Callable<InputStream>> entry : entries.entrySet()) {
				    	 
				        zos.putNextEntry(new ZipEntry(entry.getKey()));
				        
				        try (InputStream entryStream = entry.getValue().call()) {			        	
				        	IOUtils.copy(entryStream, zos);			        
				        }
				        
				        zos.closeEntry();				
				    }
			    } catch (Exception e) {
			    	logger.error("error in copyin dataset input stream to zip output stream", e);
			    	throw new InternalServerErrorException("error in copyin dataset input stream to zip output stream");
			    }
			}
		};
	}
    
    private Callable<InputStream> toCallable(String str) {
    	return new Callable<InputStream>() {
			@Override
			public InputStream call() throws Exception {
				return IOUtils.toInputStream(str);
			}
		};
    }
    
	public SessionWorkerResource(ServiceLocatorClient serviceLocator) {
		this.serviceLocator = serviceLocator;
	}

	@SuppressWarnings("unchecked")
	@POST
	@Produces(MediaType.APPLICATION_JSON)
	@Path("{sessionId}/datasets/{datasetId}")
	@Transaction
    public Response post(@PathParam("sessionId") UUID sessionId, @PathParam("datasetId") UUID zipDatasetId, @Context SecurityContext sc) throws RestException, IOException {
		
		// curl localhost:8009/sessions/8997e0d1-1c0a-4295-af3f-f191c96e589a/datasets/8997e0d1-1c0a-4295-af3f-f191c96e589a -I -X POST --user token:<TOKEN>
				
		StaticCredentials credentials = getUserCredentials(sc);
		RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, credentials);
		SessionDbClient sessionDb = new SessionDbClient(serviceLocator, credentials);
		
		HashMap<UUID, UUID> datasetIdMap = new HashMap<>();
		HashMap<UUID, UUID> jobIdMap = new HashMap<>();
		
		Session session = null;
		List<Dataset> datasets = null;
		List<Job> jobs = null; 
		
		ArrayList<String> warnings = new ArrayList<>();
		
		// read zip stream from the file-broker, upload extracted files back to file-broker and store 
		// metadata json files in memory
		try (ZipInputStream zipInputStream = new ZipInputStream(fileBroker.download(sessionId, zipDatasetId))) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
		
				if (entry.getName().equals(SESSION_JSON)) {
					session = RestUtils.parseJson(Session.class, IOUtils.toString(zipInputStream));
					
				} else if (entry.getName().equals(DATASETS_JSON)) {
					datasets = RestUtils.parseJson(List.class, Dataset.class, IOUtils.toString(zipInputStream));
					
				} else if (entry.getName().equals(JOBS_JSON)) {
					jobs = RestUtils.parseJson(List.class, Job.class, IOUtils.toString(zipInputStream));
					
				} else {
					// Create only dummy datasets now and update them with real dataset data later.
					// This way we don't make assumptions about the entry order.
					UUID datasetId = sessionDb.createDataset(sessionId, new Dataset());
					datasetIdMap.put(UUID.fromString(entry.getName()), datasetId);
					
					// prevent Jersey client from closing the stream after the upload
					// try-with-resources will close it after the whole zip file is read
					fileBroker.upload(sessionId, datasetId, new NonClosableInputStream(zipInputStream));
				}
			}
		}
		
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
			sessionDb.updateDataset(sessionId, dataset);
		}
		
		HashMap<String, ArrayList<String>> output = new HashMap<>();
		output.put("warnings", warnings);

		return Response.ok(output).build();
    }

	private StaticCredentials getUserCredentials(SecurityContext sc) {
		// use client's token to authenticate to sessionDb
		// alternatively we could check the authorization here, like file-broker
		String token = ((AuthPrincipal)sc.getUserPrincipal()).getTokenKey();
		return new StaticCredentials("token", token);
	}

	class NonClosableInputStream extends InputStream {

	    private InputStream in;

		public NonClosableInputStream(InputStream in) {
	        this.in = in;
	    }
		
	    @Override
	    public void close() throws IOException {
	    	logger.debug("zip input stream close ignored");
	    	//super.close();
	    }

	    public void closeForReal() throws IOException {
	    	in.close();
	    }

		@Override
		public int read() throws IOException {
			return in.read();
		}
		
		// interface requires only read() function, but reading with it would be really slow
	    @Override
	    public int read(byte[] b) throws IOException {
	    	return in.read(b);
	    }
	    
	    @Override
	    public int read(byte[] b, int off, int len) throws IOException {
	    	return in.read(b, off, len);
	    }
	}
}
