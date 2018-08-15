package fi.csc.chipster.sessionworker;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xml.sax.SAXException;

import fi.csc.chipster.comp.RestPhenodataUtils;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.MetadataEntry;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.microarray.client.session.SessionLoader;
import fi.csc.microarray.client.session.SessionLoaderImpl2;
import fi.csc.microarray.client.session.UserSession;
import fi.csc.microarray.client.session.schema2.DataType;
import fi.csc.microarray.client.session.schema2.InputType;
import fi.csc.microarray.client.session.schema2.LocationType;
import fi.csc.microarray.client.session.schema2.OperationType;
import fi.csc.microarray.client.session.schema2.ParameterType;
import fi.csc.microarray.client.session.schema2.SessionType;
import fi.csc.microarray.databeans.DataBean.Link;
import fi.csc.microarray.databeans.DataManager.StorageMethod;
import fi.csc.microarray.messaging.JobState;
import fi.csc.microarray.util.Strings;

public class XmlSession {
	
	private static final Logger logger = LogManager.getLogger();

	public static ExtractedSession extractSession(RestFileBrokerClient fileBroker, SessionDbClient sessionDb,
			UUID sessionId, UUID zipDatasetId) {
		
		try {
			if (!isValid(fileBroker, sessionId, zipDatasetId)) {
				return null;
			}
		
			SessionType sessionType = null;
			fi.csc.chipster.sessiondb.model.Session session = null;
			HashMap<String, String> entryToDatasetIdMap = null;
			HashMap<UUID, UUID> datasetIdMap = new HashMap<>();
			
			try (ZipInputStream zipInputStream = new ZipInputStream(fileBroker.download(sessionId, zipDatasetId))) {
				ZipEntry entry;
				while ((entry = zipInputStream.getNextEntry()) != null) {
					
					if (entry.getName().equals(UserSession.SESSION_DATA_FILENAME)) {
						
						sessionType = SessionLoaderImpl2.parseXml(new NonClosableInputStream(zipInputStream));
						
						// Job objects require UUID identifiers
						convertJobIds(sessionType);
						
						session = getSession(sessionType);
												
						// the session name isn't saved inside the xml session, so let's use whatever the uploader has set
						session.setName(sessionDb.getSession(sessionId).getName());
						
						// support for the older variant of the xml session version 2
						entryToDatasetIdMap = getEntryToDatasetIdMap(sessionType);						
						
					} else if (entryToDatasetIdMap.containsKey(entry.getName())) {				
								
						// Create only dummy datasets now and update them with real dataset data later
						UUID datasetId = sessionDb.createDataset(sessionId, new Dataset());
														
						datasetIdMap.put(UUID.fromString(entryToDatasetIdMap.get(entry.getName())), datasetId);					
						
						// prevent Jersey client from closing the stream after the upload
						// try-with-resources will close it after the whole zip file is read
						fileBroker.upload(sessionId, datasetId, new NonClosableInputStream(zipInputStream));				
						
					} else if (entry.getName().startsWith("source-code-")) {
						logger.info("omitted source code " + entry.getName());
					} else {
						logger.info("unknown file " + entry.getName());
					}
				}
			}			
			
			fixModificationParents(sessionType, session);
			
			convertPhenodata(sessionType, session, sessionId, datasetIdMap, fileBroker, sessionDb);
					
			return new ExtractedSession(session, datasetIdMap);
		} catch (IOException | RestException | SAXException | ParserConfigurationException | JAXBException e) {
			throw new InternalServerErrorException("failed to extract the session", e);
		}
	}

	/**
	 * Fix dataset relations of the user modified datasets 
	 * 
	 * When user creates a new dataset from the selected rows of an old dataset, the Java client
	 * creates a MODIFICATION type of link between the datasets and creates a dummy Operation
	 * object, which will converted to a Job object for the web app. The new client doesn't 
	 * use links, but gets dataset relations from the Job inputs. However, the Java client 
	 * doesn't create the input definitions for the dummy job, so the web app wouldn't be able 
	 * to show them. This method creates the Input objects for the Jobs to
	 * show these the dataset relationship correctly in the web app.
	 * 
	 * @param sessionType
	 * @param session
	 * @throws RestException
	 */
	private static void fixModificationParents(SessionType sessionType, Session session) throws RestException {
		
		for (DataType dataType : sessionType.getData()) {
						
			UUID datasetId = UUID.fromString(dataType.getDataId());
			Dataset dataset = session.getDatasets().get(datasetId);			
			Job job = session.getJobs().get(dataset.getSourceJob());
			
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

	private static void convertPhenodata(SessionType sessionType, Session session, UUID sessionId, HashMap<UUID, UUID> datasetIdMap, RestFileBrokerClient fileBroker, SessionDbClient sessionDb) throws RestException {
		
		HashSet<UUID> convertedPhenodatas = new HashSet<>();
		
		for (DataType dataType : sessionType.getData()) {
			
			if (dataType.getName().toLowerCase().endsWith(".tsv")) {
				DataType phenodataDataType = findPhenodata(sessionType, session, dataType);
				
				if (phenodataDataType == null) {
					continue;
				}
				
				UUID oldPhenodataId = UUID.fromString(phenodataDataType.getDataId());
				UUID newPhenodataId = datasetIdMap.get(oldPhenodataId);
				
				try (InputStream phenodata = fileBroker.download(sessionId, newPhenodataId)) {
					
					ArrayList<MetadataEntry> newPhenodata = RestPhenodataUtils.parseMetadata(phenodata, false, "");
					
					session.getDatasets().get(UUID.fromString(dataType.getDataId())).setMetadata(newPhenodata);
					
					convertedPhenodatas.add(oldPhenodataId);
				
				} catch (IOException | RestException e) {
					logger.error("failed to get the phenodata file", e);
				}
			}
		}
		
		// delete the old phenodata  files to avoid confusion
		for (UUID datasetId : convertedPhenodatas) {
			session.getDatasetCollection().removeIf(d -> datasetId.equals(d.getDatasetId()));
			sessionDb.deleteDataset(sessionId, datasetIdMap.get(datasetId));
			datasetIdMap.remove(datasetId);
		}
	}

	private static DataType findPhenodata(SessionType sessionType, Session session, DataType dataType) {

		DataType parent = dataType;
		do {
			
			List<DataType> phenodatas = getReverseLinked(sessionType, parent, Link.ANNOTATION);
			
			if (phenodatas.size() == 1) {
				return phenodatas.get(0);
			} else if (phenodatas.size() > 1) {
				// multiple phenodatas, give up
				break;
			}			
		} while ((parent = getParent(sessionType, parent)) != null);
		return null;
	}
	
	private static List<DataType> getLinked(SessionType sessionType, DataType dataType, Link linkType) {
		
		Set<String> linkedDataIds = dataType.getLink().stream()
			.filter(l -> linkType == Link.valueOf(l.getType()))
			.map(l -> l.getTarget())
			.collect(Collectors.toSet());
		
		return sessionType.getData().stream()
				.filter(d -> linkedDataIds.contains(d.getId()))
				.collect(Collectors.toList());

	}
	
	private static List<DataType> getReverseLinked(SessionType sessionType, DataType dataType, Link linkType) {
		
		return sessionType.getData().stream()
			.filter(d -> d.getLink().stream()
				.filter(l -> linkType == Link.valueOf(l.getType()))
				.filter(l -> dataType.getId().equals(l.getTarget()))
				.findAny().isPresent())
			.collect(Collectors.toList());
	}
	
	private static DataType getParent(SessionType sessionType, DataType dataType) {
		
		List<DataType> parents = getLinked(sessionType, dataType, Link.DERIVATION);
		
		if (parents.size() == 1) {
			return parents.get(0);			
		}
		// none or many parents, give up
		return null;
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
	 * There are two variants of version 2 xml sessions: the zip entries of the data files 
	 * in the older variant are named as "file-0", "file-1" and so on while the newer uses the
	 * dataId for the entry name. Map entry names to dataIds to support the older variant. In the 
	 * newer variant the key and value will be the same.
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

	private static boolean isValid(RestFileBrokerClient fileBroker, UUID sessionId, UUID zipDatasetId) throws IOException, RestException, SAXException, ParserConfigurationException {
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
					throw new BadRequestException("old session format is not supported, please save the session again with the latest Java client");
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
		
		session.setDatasets(getDatasets(sessionType.getData()));
		session.setJobs(getJobs(sessionType.getOperation()));
		
		return session;
	}

	private static Map<UUID, Job> getJobs(List<OperationType> operationTypes) {		
		return operationTypes.stream()
				.map(XmlSession::getJob)
				.collect(Collectors.toMap(Job::getJobId, j -> j));		
	}

	private static ArrayList<Parameter> getParameters(List<ParameterType> parameterTypes) {
		return parameterTypes.stream()
				.map(XmlSession::getParameter)
				.collect(Collectors.toCollection(ArrayList::new));
	}

	private static ArrayList<Input> getInputs(List<InputType> inputTypes) {
		return inputTypes.stream()
				.map(XmlSession::getInput)
				.collect(Collectors.toCollection(ArrayList::new));
	}

	private static Map<UUID, Dataset> getDatasets(List<DataType> dataTypes) {
		return dataTypes.stream()
				.map(XmlSession::getDataset)
				.collect(Collectors.toMap(Dataset::getDatasetId, d -> d));					
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
			job.setJobId(UUID.fromString(operationType.getId()));
		}
		if (operationType.getStartTime() != null) {				
			job.setStartTime(operationType.getStartTime().toGregorianCalendar().toInstant());
		}
		if (operationType.getEndTime() != null) {
			job.setEndTime(operationType.getEndTime().toGregorianCalendar().toInstant());
		}
		job.setModule(operationType.getModule());
		job.setToolCategory(operationType.getCategory());
		job.setScreenOutput(Strings.delimit(operationType.getOutput(), "\n"));			
		job.setState(JobState.COMPLETED);
		job.setToolDescription(operationType.getName().getDescription());
		job.setToolId(operationType.getName().getId());
		job.setToolName(operationType.getName().getDisplayName());

		job.setInputs(getInputs(operationType.getInput()));
		job.setParameters(getParameters(operationType.getParameter()));
		
		//job.setSourceCode(sourceCode);	

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
		
		dataset.setDatasetId(UUID.fromString(dataType.getDataId()));
		dataset.setName(dataType.getName());
		dataset.setNotes(dataType.getNotes());
		dataset.setX(dataType.getLayoutX());
		dataset.setY(dataType.getLayoutY());
		dataset.setCreated(dataType.getCreationTime().toGregorianCalendar().toInstant());
		
		dataset.setSourceJob(UUID.fromString(dataType.getResultOf()));
		
		return dataset;
	}
}
