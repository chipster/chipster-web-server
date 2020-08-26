package fi.csc.chipster.sessionworker;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
	
	private static final Logger logger = LogManager.getLogger();
	
	// original prefix in v1
	private static final String FILE_FORMAT_PREFIX_A="chipster-session-file-format-v";
	// shorter prefix since V5
	private static final String FILE_FORMAT_PREFIX_B="chipster-session-file-V";
	
	// let's try to keep this in line with the session-db flyway migration versions
	// implement equivalent migrations in migrate() for old session files
	private static final String DIR_FILE_FORMAT_LATEST = FILE_FORMAT_PREFIX_B + "6";
	
	private static final String SESSION_JSON = "session.json";
	private static final String DATASETS_JSON = "datasets.json";
	private static final String JOBS_JSON = "jobs.json";
	
	protected static final List<String> compressedExtensions = Arrays.asList(new String[] {".gz", ".zip", ".bam", ".Robj"});
	
	public static ExtractedSession extractSession(RestFileBrokerClient fileBroker, SessionDbClient sessionDb, UUID sessionId, UUID zipDatasetId) throws IOException, RestException {	
		
		String jsonSession = null;
		Map<UUID, Dataset> datasetMap = null;
		String jsonJobs = null;
		Integer version = null;
		
		// read zip stream from the file-broker, upload extracted files back to file-broker and store 
		// metadata json files in memory
		try (ZipInputStream zipInputStream = new ZipInputStream(fileBroker.download(sessionId, zipDatasetId))) {
			
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				
				if (entry.isDirectory()) {
					// skip folders
					continue;
				}
				
				if (RestUtils.basename(entry.getName()).startsWith(".")) {
					// skip hidden files (created by e.g. OSX Archive Utility)
					continue;
				}
				
				if (!isCompatible(entry.getName())) {
					// we will close the connection without reading the whole input stream
					// to fix this we would need create a limited InputStream with a HTTP range
					
					// this class doesn't recognize the file format
					return null;
				}
				
				String entryName = getFilename(entry.getName());
				if (version == null) {
					version = getVersion(entry.getName());
				} else if (version != getVersion(entry.getName())){
					throw new IllegalArgumentException("entry " + entry.getName() + " version differs from previous: " + version);
				}				
				
				if (entryName.equals(SESSION_JSON)) {
					jsonSession = RestUtils.toString(zipInputStream);
					zipInputStream.closeEntry();
					
				} else if (entryName.equals(DATASETS_JSON)) {
					String jsonDatasets = RestUtils.toString(zipInputStream);
					// parse datasets right away to get the file sizes
					// there has been no need for the migrations here so far  
					datasetMap = parseDatasets(jsonDatasets);
					zipInputStream.closeEntry();
					
				} else if (entryName.equals(JOBS_JSON)) {
					jsonJobs = RestUtils.toString(zipInputStream);
					zipInputStream.closeEntry();
					
				} else {
					// Create only dummy datasets now and update them with real dataset data later.
					// This way we don't make assumptions about the entry order.
					UUID datasetId = UUID.fromString(entryName);
					Dataset dummyDataset = new Dataset();
					dummyDataset.setDatasetIdPair(sessionId, datasetId);
					sessionDb.createDataset(sessionId, dummyDataset);
										
					Long size = null;
					/* 
					 * Try to get the file size for the file-broker
					 * 
					 * File-broker uses this size information to choose a file-storage which has enough 
					 * free space. In the ZipInputStream the size is available only after reading each 
					 * entry. ZipFile can read this information from the zip file directory in the end 
					 * of the file but ZipOutputStream can't do that kind of random-access reads.
					 * 
					 * Chipster writes the metadata to the beginning of the file, so we should get the
					 * size from there. If the zip was made with some other program, this may not be 
					 * available yet.
					 */
					if (datasetMap != null) {
						Dataset dataset = datasetMap.get(datasetId);
						if (dataset != null && dataset.getFile() != null) {
							size = dataset.getFile().getSize();
						}
					}
					
					// close only the entry instead of the whole zip when Jersey client finished the upload
					// try-with-resources will close the zipInputStream after the whole zip file is read
					fileBroker.upload(sessionId, datasetId, new EntryClosingInputStream(zipInputStream), size);
				}
			}
		}
				
		return migrate(version, jsonSession, datasetMap, jsonJobs);
	}
	
	private static Map<UUID, Dataset> parseDatasets(String jsonDatasets) {
		@SuppressWarnings("unchecked")
		List<Dataset> datasets = RestUtils.parseJson(List.class, Dataset.class, jsonDatasets);
		Map<UUID, Dataset> datasetMap = datasets.stream().collect(Collectors.toMap(d -> d.getDatasetId(), d -> d));
		
		return datasetMap;
	}
	
	@SuppressWarnings("unchecked")
	private static ExtractedSession migrate(Integer version, String jsonSession, Map<UUID, Dataset> datasetMap, String jsonJobs) {

		Session session;
		List<Job> jobs;
		
		// so far we have been able to use the latest classes to parse all object versions
		session = RestUtils.parseJson(Session.class, jsonSession);
		jobs = RestUtils.parseJson(List.class, Job.class, jsonJobs);
		
		if (version < 5) {
			// since V5 dataset.metadataFiles has been an empty array instead of null
			for (Dataset d : datasetMap.values()) {
				if (d.getMetadataFiles() == null) {
					d.setMetadataFiles(new ArrayList<>());
				} else {
					logger.warn("dataset " + d.getName() + " has metadataFiles is not null despite the session version " + version + ", keeping it");
				}
			}
		} else if (version >= 5) {
			// since V5 flyway migration should have created the metadataFiles array
			for (Dataset d : datasetMap.values()) {
				if (d.getMetadataFiles() == null) {
					throw new IllegalStateException("dataset " + d.getName() + " metadataFiles is null despite the session version " + version);
				}					
			}
		}
		
		Map<UUID, Job> jobMap = jobs.stream().collect(Collectors.toMap(j -> j.getJobId(), j -> j));
		
		return new ExtractedSession(session, datasetMap, jobMap);
	}

	private static boolean isCompatible(String entryName) {
		return entryName.contains("/" + FILE_FORMAT_PREFIX_A) 
				|| entryName.contains("/" + FILE_FORMAT_PREFIX_B);
	}
	
	private static int getVersion(String entryName) {
		String name = removeFormatPrefix(entryName);
		
		String versionString = name.substring(0, name.indexOf("/"));
		
		return Integer.parseInt(versionString);
	}
	
	private static String removeFormatPrefix(String entryName) {
		String name = null;
		if (entryName.contains("/" + FILE_FORMAT_PREFIX_A)) {
			name = entryName.substring(entryName.indexOf(FILE_FORMAT_PREFIX_A) + FILE_FORMAT_PREFIX_A.length());
		} else if(entryName.contains("/" + FILE_FORMAT_PREFIX_B)) {
			name = entryName.substring(entryName.indexOf(FILE_FORMAT_PREFIX_B) + FILE_FORMAT_PREFIX_B.length());
		} else {
			throw new IllegalArgumentException("illegal entry name, '" + FILE_FORMAT_PREFIX_A + "' or '" + FILE_FORMAT_PREFIX_B + "' not found");
		}
		return name;
	}
	
	private static String getFilename(String entryName) {
		String name = removeFormatPrefix(entryName);
		int version = getVersion(entryName);
		String versionString = "" + version + "/";
		
		return name.substring(versionString.length());
	}

	public static void packageSession(SessionDbClient sessionDb, RestFileBrokerClient fileBroker, Session session, UUID sessionId, ArrayList<InputStreamEntry> entries) throws RestException {
		
		Collection<Dataset> datasets = sessionDb.getDatasets(sessionId).values();
		Collection<Job> jobs = sessionDb.getJobs(sessionId).values();
		
		// we can't know if a username would refer to same person on the server where this session will be opened  
		session.setRules(null);
		
		// remove datasets that don't have File. These are probably broken uploads 
		datasets = datasets.stream()
			.filter(d -> {
				if (d.getFile() == null || d.getFile().getFileId() == null) {
					logger.info("skipping null dataset, sessionId " + session.getSessionId() + " datasetId " + d.getDatasetId());
					return false;
				}
				return true;
			})	
			.collect(Collectors.toList());
		
		
		String sessionJson = RestUtils.asJson(session, true);
		String datasetsJson = RestUtils.asJson(datasets, true);
		String jobsJson = RestUtils.asJson(jobs, true);
		
		String sessionName = session.getName();
		
		/*
		 * Add DIR_FILE_FORMAT to all entry names to recognize this file format even if it's 
		 * packaged with some other zip tool and the order of entries may have changed.
		 * 
		 * Add the session name in front to make the extracted folder name more meaningful and 
		 * to prevent the DIR_FILE_FORMAT being altered when there is already a folder with 
		 * that name. The session name here is just for humans, it doesn't matter if that 
		 * is changed.
		 * 
		 * Seems to tolerate at least extraction and packaging with OSX Archive Utility. 
		 * 
		 */
		entries.add(new InputStreamEntry(sessionName + "/" + DIR_FILE_FORMAT_LATEST + "/" + SESSION_JSON, sessionJson));
		entries.add(new InputStreamEntry(sessionName + "/" + DIR_FILE_FORMAT_LATEST + "/" + DATASETS_JSON, datasetsJson));
		entries.add(new InputStreamEntry(sessionName + "/" + DIR_FILE_FORMAT_LATEST + "/" + JOBS_JSON, jobsJson));
		
		for (Dataset dataset : datasets) {
			entries.add(new InputStreamEntry(sessionName + "/" + DIR_FILE_FORMAT_LATEST + "/" + dataset.getDatasetId().toString(), new Callable<InputStream>() {
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
			if (name != null && name.endsWith(extension)) {
				// there is no point to compress files that are compressed already
				return Deflater.NO_COMPRESSION;
			}
		}
		// beneficial only when the client connection is slower than compression throughput * compression ratio
		return Deflater.BEST_SPEED;
	}
}
