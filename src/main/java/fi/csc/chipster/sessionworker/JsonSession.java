package fi.csc.chipster.sessionworker;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Session;

public class JsonSession {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();
	
	private static final String SESSION_JSON = "session.json";
	private static final String DATASETS_JSON = "datasets.json";
	private static final String JOBS_JSON = "jobs.json";
	
	protected static final List<String> compressedExtensions = Arrays.asList(new String[] {".gz", ".zip", ".bam"});
	
	@SuppressWarnings("unchecked")
	public static ExtractedSession extractSession(RestFileBrokerClient fileBroker, SessionDbClient sessionDb, UUID sessionId, UUID zipDatasetId) throws IOException, RestException {
		
		try (ZipInputStream zipInputStream = new ZipInputStream(fileBroker.download(sessionId, zipDatasetId))) {
			ZipEntry entry = zipInputStream.getNextEntry();		
			if (entry == null || !entry.getName().equals(SESSION_JSON)) {
				// we will close the connection without reading the whole input stream
				// to fix this we would need create a limited InputStream with a HTTP range
				return null;
			}
		}
		
		Session session = null;
		List<Dataset> datasets = null;
		List<Job> jobs = null;
		HashMap<UUID, UUID> datasetIdMap = new HashMap<>();
		
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
		
		session.setDatasets(datasets.stream().collect(Collectors.toMap(d -> d.getDatasetId(), d -> d)));
		session.setJobs(jobs.stream().collect(Collectors.toMap(j -> j.getJobId(), j -> j)));
		
		return new ExtractedSession(session, datasetIdMap);
	}
	
	public static void packageSession(SessionDbClient sessionDb, RestFileBrokerClient fileBroker, Session session, UUID sessionId, ArrayList<InputStreamEntry> entries) throws RestException {
		
		String sessionJson = RestUtils.asJson(session, true);
		String datasetsJson = RestUtils.asJson(sessionDb.getDatasets(sessionId).values(), true);
		String jobsJson = RestUtils.asJson(sessionDb.getJobs(sessionId).values(), true);
		
		entries.add(new InputStreamEntry(SESSION_JSON, sessionJson));
		entries.add(new InputStreamEntry(DATASETS_JSON, datasetsJson));
		entries.add(new InputStreamEntry(JOBS_JSON, jobsJson));
		
		// sort by date to keep the files in order
		ArrayList<Dataset> sortedDatasets = new ArrayList<>(sessionDb.getSession(sessionId).getDatasets().values());
		sortedDatasets.sort(new Comparator<Dataset>() {
			@Override
			public int compare(Dataset o1, Dataset o2) {
				return o1.getFile().getCreated().compareTo(o2.getFile().getCreated());
			}
		});
		
		for (Dataset dataset : sortedDatasets) {
			entries.add(new InputStreamEntry(dataset.getDatasetId().toString(), new Callable<InputStream>() {
				@Override
				public InputStream call() throws Exception {					
					return fileBroker.download(sessionId, dataset.getDatasetId());
				}
			}, getCompressionLevel(dataset.getName())));
		}
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
     * NO_COMPRESSION is enough for browsers, which don't have more than 1Gb/s connections anyway. CLI client on a server could
     * have better network connection, but it could save files to a directory instead of a zip file. 
     * 
	 * @param name
	 * @return
	 */
	private static int getCompressionLevel(String name) {
		for (String extension : compressedExtensions) {
			if (name.endsWith(extension)) {
				// there is no point to compress files that are compressed already
				return Deflater.NO_COMPRESSION;
			}
		}
		// beneficial only when the client connection is slower than compression throughput * compression ratio
		return Deflater.BEST_SPEED;
	}
}
