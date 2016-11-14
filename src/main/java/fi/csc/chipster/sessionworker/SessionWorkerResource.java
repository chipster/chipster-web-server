package fi.csc.chipster.sessionworker;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
	
	protected static final List<String> compressedExtensions = Arrays.asList(new String[] {".gz", ".zip", ".bam"});
	
	private static Logger logger = LogManager.getLogger();
	private ServiceLocatorClient serviceLocator;
	
	public static class InputStreamEntry {
		private String name;
		private Callable<InputStream> inputStreamCallable;
		private int compressionLevel;

		public InputStreamEntry(String name, String content) {
			this(name, toCallable(content));
		}
		
		public InputStreamEntry(String name, Callable<InputStream> inputStreamCallable) {
			this(name, inputStreamCallable, Deflater.BEST_SPEED);
		}
		
		/**
		 * @param name Name of the zip file
		 * @param inputStreamCallable Callable, which returns the InputStream of the file content
		 * @param compressionLevel Compression level for this entry (e.g. Deflater.NO_COMPRESSION)
		 */
		public InputStreamEntry(String name, Callable<InputStream> inputStreamCallable, int compressionLevel) {
			this.name = name;
			this.inputStreamCallable = inputStreamCallable;
			this.compressionLevel = compressionLevel;
		}
		
		public int getCompressionLevel() {
			return compressionLevel;
		}
		
		public void setCompressionLevel(int compressionLevel) {
			this.compressionLevel = compressionLevel;
		}
		
		public Callable<InputStream> getInputStreamCallable() {
			return inputStreamCallable;
		}
		
		public void setInputStreamCallable(Callable<InputStream> inputStreamCallable) {
			this.inputStreamCallable = inputStreamCallable;
		}
		
		public String getName() {
			return name;
		}
		
		public void setName(String name) {
			this.name = name;
		}
	}
	
    @GET
    @Path("{sessionId}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Transaction
    public Response get(@PathParam("sessionId") UUID sessionId, @Context SecurityContext sc) throws IOException, RestException {
    	    
    	StaticCredentials credentials = getUserCredentials(sc);
    	RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, credentials);
		SessionDbClient sessionDb = new SessionDbClient(serviceLocator, credentials);
		
		ArrayList<InputStreamEntry> entries = new ArrayList<>();
		
		Session session = sessionDb.getSession(sessionId);
		
		String sessionJson = RestUtils.asJson(session, true);
		String datasetsJson = RestUtils.asJson(sessionDb.getDatasets(sessionId).values(), true);
		String jobsJson = RestUtils.asJson(sessionDb.getJobs(sessionId).values(), true);
		
		entries.add(new InputStreamEntry(SESSION_JSON, sessionJson));
		entries.add(new InputStreamEntry(DATASETS_JSON, datasetsJson));
		entries.add(new InputStreamEntry(JOBS_JSON, jobsJson));
		
		for (Dataset dataset : sessionDb.getSession(sessionId).getDatasets().values()) {
			entries.add(new InputStreamEntry(dataset.getDatasetId().toString(), new Callable<InputStream>() {
				@Override
				public InputStream call() throws Exception {
					return fileBroker.download(sessionId, dataset.getDatasetId());
				}
			}, getCompressionLevel(dataset.getName())));
		}
		
		ResponseBuilder response = Response.ok(getZipStreamingOuput(entries));
		RestUtils.configureForDownload(response, session.getName()+ ".zip");
		return response.build();
    }

	/** 
     * Download speed from a localhost server (NGS session where all the big files are already compressed)
     * DEFAULT: 		30s, 13MB/s
     * BEST_SPEED:		29s, 13MB/s
     * NO_COMPRESSION:	9s, 110MB/s
     * 
     * Even the NO_COMPRESSION option limits the throughput considerably. There is another mode setMethod(ZipOutputStream.STORED),
     * but then the zip format needs to know the checksum and file sizes already before the zip entry is written. This is possible 
     * only by reading the file twice, first to count the checksum and stream length and then again to actually write the data. Both
     * phases attained a speed of 200-300MB/s, but then the real throughput is only half of the disk read speed.
     * 
     * This is enough for browsers, which don't have more than 1Gb/s connections anyway. CLI client on a server could
     * have better network connection, but it could save files to a directory instead of a zip file. 
     * 
	 * @param name
	 * @return
	 */
	private int getCompressionLevel(String name) {
		for (String extension : compressedExtensions) {
			if (name.endsWith(extension)) {
				// there is no point to compress files that are compressed already
				return Deflater.NO_COMPRESSION;
			}
		}
		// beneficial only when the client connection is slower than compression throughput * compression ratio
		return Deflater.BEST_SPEED;
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
			    	logger.error("error in copyin dataset input stream to zip output stream", e);
			    	throw new InternalServerErrorException("error in copyin dataset input stream to zip output stream");
			    }
			}
		};
	}
    
    private static Callable<InputStream> toCallable(String str) {
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
