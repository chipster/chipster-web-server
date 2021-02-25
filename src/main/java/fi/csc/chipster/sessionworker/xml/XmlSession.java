package fi.csc.chipster.sessionworker.xml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
//import java.util.zip.ZipEntry;
//import java.util.zip.ZipInputStream;
import java.util.zip.ZipInputStream;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xml.sax.SAXException;

import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.MetadataFile;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessionworker.ExtractedSession;
import fi.csc.chipster.sessionworker.xml.DataBean.Link;
import fi.csc.chipster.sessionworker.xml.DataManager.StorageMethod;
import fi.csc.chipster.sessionworker.xml.schema2.DataType;
import fi.csc.chipster.sessionworker.xml.schema2.InputType;
import fi.csc.chipster.sessionworker.xml.schema2.LocationType;
import fi.csc.chipster.sessionworker.xml.schema2.OperationType;
import fi.csc.chipster.sessionworker.xml.schema2.ParameterType;
import fi.csc.chipster.sessionworker.xml.schema2.SessionType;

public class XmlSession {

	private static final Logger logger = LogManager.getLogger();

	public static ExtractedSession extractSession(RestFileBrokerClient fileBroker, SessionDbClient sessionDb,
			UUID sessionId, UUID zipDatasetId, File tempDir) {

		try {
			if (!isValid(fileBroker, sessionId, zipDatasetId)) {
				return null;
			}

			SessionType sessionType = null;
			fi.csc.chipster.sessiondb.model.Session session = null;
			Map<UUID, Dataset> datasetMap = null;
			Map<UUID, Job> jobMap = null;
			HashMap<String, String> entryToDatasetIdMap = null;
			Map<String, String> screenOutputMap = new HashMap<>();
			
			ArrayList<String> warnings = new ArrayList<>();
	    	ArrayList<String> errors = new ArrayList<>();

			/*
			 * The default zip implementation can't read from InputStream over 4 GB files
			 * written by TrueZip ( java.util.zip.ZipException: invalid entry size (expected
			 * 0 but got X bytes).
			 * 
			 * Seems to work fine if we first download the file, but requires potentially
			 * hundreds of gigabytes of disk space on the session-worker.
			 */
			File localTempZip = new File(tempDir, sessionId + ".zip");
			logger.info("donwload session to " + localTempZip.toString());
			fileBroker.download(sessionId, zipDatasetId, localTempZip);

			logger.info("extract session " + localTempZip.toString());
			try (ZipFile zipFile = new ZipFile(localTempZip)) {

				Enumeration<? extends ZipEntry> entries = zipFile.entries();

				while (entries.hasMoreElements()) {

					ZipEntry entry = entries.nextElement();

					try (InputStream entryInputStream = zipFile.getInputStream(entry)) {

						if (entry.getName().equals(UserSession.SESSION_DATA_FILENAME)) {

							sessionType = SessionLoaderImpl2.parseXml(entryInputStream);

							// Job objects require UUID identifiers
							convertJobIds(sessionType);

							session = getSession(sessionType);
							datasetMap = getDatasets(sessionType.getData(), warnings);
							jobMap = getJobs(sessionType.getOperation());

							// the session name isn't saved inside the xml session, so let's use whatever
							// the uploader has set
							session.setName(sessionDb.getSession(sessionId).getName());

							// support for the older variant of the xml session version 2
							entryToDatasetIdMap = getEntryToDatasetIdMap(sessionType);

						} else if (entryToDatasetIdMap.containsKey(entry.getName())) {

							UUID datasetId = UUID.fromString(entryToDatasetIdMap.get(entry.getName()));

							// Create only dummy datasets now and update them with real dataset data later,
							// because it's done this way in JsonSession at the moment (although here we
							// rely on the
							// zip entry order and could create the final datasets already now).
							Dataset dummyDataset = new Dataset();
							dummyDataset.setDatasetIdPair(sessionId, datasetId);

							sessionDb.createDataset(sessionId, dummyDataset);
														
							/* Size should be available
							 * 
							 * When reading the zip from a file, ZipFile can read the
							 * size from the directory in the end of the file.
							 */
							Long size = null;
							
							if (entry.getSize() >= 0) {
								size = entry.getSize();
							}

							fileBroker.upload(sessionId, datasetId, entryInputStream, size);

						} else if (entry.getName().startsWith("source-code-")) {
							// source code in the old session is actually a screen output
							String screenOutput = IOUtils.toString(entryInputStream, Charset.defaultCharset());
							screenOutputMap.put(entry.getName(), screenOutput);

						} else {
							logger.info("unknown file " + entry.getName());
						}
					}
				}
			} finally {
				localTempZip.delete();
			}

			addScreenOutputs(jobMap, screenOutputMap);

			fixModificationParents(sessionType, session, datasetMap, jobMap);

			convertPhenodata(sessionType, session, sessionId, fileBroker, sessionDb, datasetMap);

			return new ExtractedSession(session, datasetMap, jobMap, warnings, errors);
		} catch (IOException | RestException | SAXException | ParserConfigurationException | JAXBException e) {
			throw new InternalServerErrorException("failed to extract the session", e);
		}
	}

	private static void addScreenOutputs(Map<UUID, Job> jobMap, Map<String, String> screenOutputMap) {
		for (Job job : jobMap.values()) {
			// getJob() saved the file name to the Job.screenOutput field
			String filename = job.getScreenOutput();
			// replace it with the file content (or null if not found)
			job.setScreenOutput(screenOutputMap.get(filename));
		}
	}

	/**
	 * Fix dataset relations of the user modified datasets
	 * 
	 * When user creates a new dataset from the selected rows of an old dataset, the
	 * Java client creates a MODIFICATION type of link between the datasets and
	 * creates a dummy Operation object, which will converted to a Job object for
	 * the web app. The new client doesn't use links, but gets dataset relations
	 * from the Job inputs. However, the Java client doesn't create the input
	 * definitions for the dummy job, so the web app wouldn't be able to show them.
	 * This method creates the Input objects for the Jobs to show these the dataset
	 * relationship correctly in the web app.
	 * 
	 * @param sessionType
	 * @param session
	 * @param datasetMap
	 * @param jobMap
	 * @throws RestException
	 */
	private static void fixModificationParents(SessionType sessionType, Session session, Map<UUID, Dataset> datasetMap,
			Map<UUID, Job> jobMap) throws RestException {

		for (DataType dataType : sessionType.getData()) {

			UUID datasetId = UUID.fromString(dataType.getDataId());
			Dataset dataset = datasetMap.get(datasetId);
			Job job = jobMap.get(dataset.getSourceJob());

			if (job != null && "operation-definition-id-user-modification".equals(job.getToolId())) {
				List<DataType> parents = getLinked(sessionType, dataType, Link.MODIFICATION);

				if (parents.size() == 1) {
					Input input = new Input();
					String dataId = parents.get(0).getDataId();
					input.setDatasetId(dataId);
					List<Input> inputs = new ArrayList<>();
					inputs.add(input);
					job.setInputs(inputs);
				}
			}
		}
	}

	private static void convertPhenodata(SessionType sessionType, Session session, UUID sessionId,
			RestFileBrokerClient fileBroker, SessionDbClient sessionDb, Map<UUID, Dataset> datasetMap)
			throws RestException {

		HashSet<UUID> convertedPhenodatas = new HashSet<>();

		for (DataType dataType : sessionType.getData()) {

			if (dataType.getName().toLowerCase().endsWith(".tsv")) {
				DataType phenodataDataType = findPhenodata(sessionType, session, dataType);
				if (phenodataDataType == null) {
					continue;
				}

				UUID phenodataId = UUID.fromString(phenodataDataType.getDataId());
				try (InputStream phenodata = fileBroker.download(sessionId, phenodataId)) {
					String phenodataString = IOUtils.toString(phenodata, "UTF-8");
					List<MetadataFile> metadataFiles = new ArrayList<>();
					metadataFiles.add(new MetadataFile("phenodata.tsv", phenodataString));
					datasetMap.get(UUID.fromString(dataType.getDataId())).setMetadataFiles(metadataFiles);
					convertedPhenodatas.add(phenodataId);
				} catch (IOException | RestException e) {
					logger.error("failed to get the phenodata file", e);
				}
			}
		}

		// delete the old phenodata files to avoid confusion
		for (

		UUID datasetId : convertedPhenodatas) {
			sessionDb.deleteDataset(sessionId, datasetId);
			datasetMap.remove(datasetId);
		}
	}

	private static DataType findPhenodata(SessionType sessionType, Session session, DataType dataType) {
		List<DataType> phenodatas = getReverseLinked(sessionType, dataType, Link.ANNOTATION);
		return phenodatas.size() == 1 ? phenodatas.get(0) : null;
	}

	private static List<DataType> getLinked(SessionType sessionType, DataType dataType, Link linkType) {

		Set<String> linkedDataIds = dataType.getLink().stream().filter(l -> linkType == Link.valueOf(l.getType()))
				.map(l -> l.getTarget()).collect(Collectors.toSet());

		return sessionType.getData().stream().filter(d -> linkedDataIds.contains(d.getId()))
				.collect(Collectors.toList());

	}

	private static List<DataType> getReverseLinked(SessionType sessionType, DataType dataType, Link linkType) {

		return sessionType.getData().stream()
				.filter(d -> d.getLink().stream().filter(l -> linkType == Link.valueOf(l.getType()))
						.filter(l -> dataType.getId().equals(l.getTarget())).findAny().isPresent())
				.collect(Collectors.toList());
	}

	/**
	 * Generate UUID identifiers for operations
	 * 
	 * @param sessionType
	 */
	private static void convertJobIds(SessionType sessionType) {
		HashMap<String, String> jobIdMap = new HashMap<>();

		for (OperationType operationType : sessionType.getOperation()) {
			UUID newId = RestUtils.createUUID();
			jobIdMap.put(operationType.getId(), newId.toString());
			operationType.setId(newId.toString());
		}

		for (DataType dataType : sessionType.getData()) {
			dataType.setResultOf(jobIdMap.get(dataType.getResultOf()));
		}
	}

	/**
	 * There are two variants of version 2 xml sessions: the zip entries of the data
	 * files in the older variant are named as "file-0", "file-1" and so on while
	 * the newer uses the dataId for the entry name. Map entry names to dataIds to
	 * support the older variant. In the newer variant the key and value will be the
	 * same.
	 * 
	 * @param sessionType
	 * @return
	 */
	private static HashMap<String, String> getEntryToDatasetIdMap(SessionType sessionType) {
		HashMap<String, String> entryToDatasetIdMap = new HashMap<>();

		for (DataType dataType : sessionType.getData()) {
			boolean found = false;
			for (LocationType locationType : dataType.getLocation()) {
				if (StorageMethod.LOCAL_SESSION_ZIP.toString().equals(locationType.getMethod())) {
					found = true;
					String url = locationType.getUrl();
					String entryName = url.substring(url.indexOf("#") + 1);
					entryToDatasetIdMap.put(entryName, dataType.getDataId());
				}
			}
			if (!found) {
				throw new BadRequestException("file content of " + dataType.getName() + " not found");
			}
		}

		return entryToDatasetIdMap;
	}

	private static boolean isValid(RestFileBrokerClient fileBroker, UUID sessionId, UUID zipDatasetId)
			throws IOException, RestException, SAXException, ParserConfigurationException {
		try (ZipInputStream zipInputStream = new ZipInputStream(fileBroker.download(sessionId, zipDatasetId))) {
			ZipEntry entry = zipInputStream.getNextEntry();

			// we will close the connection without reading the whole input stream
			// to fix this we would need create a limited InputStream with a HTTP range
			if (entry != null && entry.getName().equals(UserSession.SESSION_DATA_FILENAME)) {
				String version = SessionLoader.getSessionVersion(zipInputStream);
				if ("2".equals(version)) {
					return true;
				}

				if ("1".equals(version)) {
					throw new BadRequestException(
							"old session format is not supported, please save the session again with the latest Java client");
				}
			}
		}
		return false;
	}

	/**
	 * Convert an old SessionType to a new Session object
	 * 
	 * @param sessionType
	 * @param jobIdMap
	 * @return
	 */
	private static fi.csc.chipster.sessiondb.model.Session getSession(SessionType sessionType) {
		fi.csc.chipster.sessiondb.model.Session session = new fi.csc.chipster.sessiondb.model.Session();

		session.setAccessed(null);
		session.setCreated(Instant.now());
		session.setNotes(sessionType.getNotes());

		return session;
	}

	private static Map<UUID, Job> getJobs(List<OperationType> operationTypes) {
		return operationTypes.stream().map(XmlSession::getJob).collect(Collectors.toMap(Job::getJobId, j -> j));
	}

	private static ArrayList<Parameter> getParameters(List<ParameterType> parameterTypes) {
		return parameterTypes.stream().map(XmlSession::getParameter).collect(Collectors.toCollection(ArrayList::new));
	}

	private static ArrayList<Input> getInputs(List<InputType> inputTypes) {
		return inputTypes.stream().map(XmlSession::getInput).collect(Collectors.toCollection(ArrayList::new));
	}

	private static Map<UUID, Dataset> getDatasets(List<DataType> dataTypes, ArrayList<String> warnings) {
		
		/*
		 * A few customer sessions have had duplicate dataIds. Created by session merge perhaps?
		 */
		List<String> duplicateIds = dataTypes.stream()
			.collect(Collectors.groupingBy(DataType::getDataId, Collectors.counting()))
			.entrySet()
			.stream()
				.filter(e -> e.getValue() > 1)
				.map(Map.Entry::getKey)
				.collect(Collectors.toList());			
		
		if (!duplicateIds.isEmpty()) {
			
			for (String dataId : duplicateIds) {				
				
				List<DataType> duplicates = dataTypes.stream()
					.filter(d -> dataId.equals(d.getDataId()))
					.collect(Collectors.toList());
				
				List<String> duplicateNames = duplicates.stream()
						.map(d ->  d.getName())
						.collect(Collectors.toList());
								
				dataTypes.removeAll(duplicates);
				dataTypes.add(duplicates.get(0));
				
				String warning = "merged datasets: " + String.join(", ", duplicateNames) + " because those had the same dataId";
				
				logger.warn(warning + " " + dataId);
				warnings.add(warning);
			}			
		}
		
		return dataTypes.stream().map(XmlSession::getDataset).collect(Collectors.toMap(Dataset::getDatasetId, d -> d));
	}

	/**
	 * Convert an old OperationType to a new Job object
	 * 
	 * @param operationType
	 * @return
	 */
	private static Job getJob(OperationType operationType) {
		Job job = new Job();

		if (operationType.getId() != null) {
			job.setJobIdPair(null, UUID.fromString(operationType.getId()));
		}
		if (operationType.getStartTime() != null) {
			job.setStartTime(operationType.getStartTime().toGregorianCalendar().toInstant());
		}
		if (operationType.getEndTime() != null) {
			job.setEndTime(operationType.getEndTime().toGregorianCalendar().toInstant());
		}
		job.setModule(operationType.getModule());
		job.setToolCategory(operationType.getCategory());
		// seems to be small integers, output dataset IDs perhaps?
		// operationType.getOutput();
		job.setState(JobState.COMPLETED);
		job.setToolDescription(operationType.getName().getDescription());
		job.setToolId(operationType.getName().getId());
		job.setToolName(operationType.getName().getDisplayName());

		job.setInputs(getInputs(operationType.getInput()));
		job.setParameters(getParameters(operationType.getParameter()));

		// the content of the sourceCodeFile is actually screenOutput
		// save the fileName to this field for a moment
		job.setScreenOutput(operationType.getSourceCodeFile());

		// the old session doesn't have sourceCode
		// job.setSourceCode(sourceCode);

		return job;
	}

	/**
	 * Convert an old ParameterType to a new Parameter object
	 * 
	 * @param parameterType
	 * @return
	 */
	private static Parameter getParameter(ParameterType parameterType) {
		Parameter parameter = new Parameter();

		parameter.setParameterId(parameterType.getName().getId());
		parameter.setDisplayName(parameterType.getName().getDisplayName());
		parameter.setDescription(parameterType.getName().getDescription());
		parameter.setValue(parameterType.getValue());

		return parameter;
	}

	/**
	 * Convert an old InputType to a new Input object
	 * 
	 * @param inputType
	 * @return
	 */
	private static Input getInput(InputType inputType) {
		Input input = new Input();

		input.setDatasetId(inputType.getDataId());
		input.setDisplayName(inputType.getName().getDisplayName());
		input.setDescription(inputType.getName().getDescription());
		input.setInputId(inputType.getName().getId());

		return input;
	}

	/**
	 * Convert an old DataType to a new Dataset object
	 * 
	 * @param dataType
	 * @return
	 */
	private static Dataset getDataset(DataType dataType) {
		Dataset dataset = new Dataset();

		dataset.setDatasetIdPair(null, UUID.fromString(dataType.getDataId()));
		dataset.setName(dataType.getName());
		dataset.setNotes(dataType.getNotes());
		dataset.setX(dataType.getLayoutX());
		dataset.setY(dataType.getLayoutY());
		dataset.setCreated(dataType.getCreationTime().toGregorianCalendar().toInstant());

		dataset.setSourceJob(UUID.fromString(dataType.getResultOf()));

		return dataset;
	}
}
