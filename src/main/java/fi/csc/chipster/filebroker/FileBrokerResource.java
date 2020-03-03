package fi.csc.chipster.filebroker;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.naming.NamingException;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.filestorage.FileStorageClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.resource.SessionDatasetResource;

@Path("/")
public class FileBrokerResource {
	
	private static final String FILE_BROKER_STORAGE_READ_ONLY_PREFIX = "file-broker-storage-read-only-";
	private static final String FILE_BROKER_STORAGE_DNS_DOMAIN_PREFIX = "file-broker-storage-dns-domain-";
	private static final String FILE_BROKER_STORAGE_DNS_PROTOCOL = "file-broker-storage-dns-protocol";
	private static final String FILE_BROKER_STORAGE_DNS_PORT = "file-broker-storage-dns-port";
	private static final String FILE_BROKER_STORAGE_NULL = "file-broker-storage-null";

	private static final String FLOW_TOTAL_CHUNKS = "flowTotalChunks";
	private static final String FLOW_CHUNK_SIZE = "flowChunkSize";
	private static final String FLOW_CHUNK_NUMBER = "flowChunkNumber";
	
	public static final String HEADER_RANGE = "Range";

	private static Logger logger = LogManager.getLogger();
	
	private Config config;
	private ServiceLocatorClient serviceLocator;

	private SessionDbClient sessionDbWithFileBrokerCredentials;

	private String sessionDbUri;
	private String sessionDbEventsUri;
		
	private Map<String, FileStorageClient> storageClients = new HashMap<>();
	private List<String> writeStorages = new ArrayList<>();
	
	Random rand = new Random();
	private AuthenticationClient authService;
	private ExecutorService updateExecutor;
	private Instant fileStoragesLastUpdated;
	private Map<String, String> dnsDomains;
	private String storageForNull;
	private Collection<String> readOnlyStorages;
	
	public FileBrokerResource(ServiceLocatorClient serviceLocator, SessionDbClient sessionDbClient, AuthenticationClient authService, Config config) {
		this.serviceLocator = serviceLocator;
		this.sessionDbWithFileBrokerCredentials = sessionDbClient;
		this.authService = authService;
		this.config = config;
		
		this.sessionDbUri = serviceLocator.getInternalService(Role.SESSION_DB).getUri();
		this.sessionDbEventsUri = serviceLocator.getInternalService(Role.SESSION_DB_EVENTS).getUri();
		
		dnsDomains = config.getConfigEntries(FILE_BROKER_STORAGE_DNS_DOMAIN_PREFIX);
		storageForNull = config.getString(FILE_BROKER_STORAGE_NULL);
		// create a new Set to be able to add new items
		readOnlyStorages = new HashSet<>(config.getConfigEntries(FILE_BROKER_STORAGE_READ_ONLY_PREFIX).values());
		
		this.updateFileStorages(true);
		
		this.updateExecutor = Executors.newCachedThreadPool();
	}
	
	private void updateFileStorages(boolean verbose) {
		
		synchronized (storageClients) {
			
			HashMap<String, FileStorageClient> oldStorageClients = new HashMap<>(storageClients);

			Set<String> dnsStorages = new HashSet<>();
			Map<String, String> storageUrls = new HashMap<>();
			
			for (String dnsConfigKey : dnsDomains.keySet()) {
				
				String dnsDomain = dnsDomains.get(dnsConfigKey);
				// use default or allow to be configured for each dns domain
				String protocol = config.getString(FILE_BROKER_STORAGE_DNS_PROTOCOL, dnsConfigKey);
				String port = config.getString(FILE_BROKER_STORAGE_DNS_PORT, dnsConfigKey);
				
				if (dnsDomain != null && !dnsDomain.isEmpty()) {
					if (verbose) {
						logger.info("get file-storages from DNS record " + dnsDomain);
					}
					try {
						dnsStorages = DnsUtils.getSrvRecords(dnsDomain);
					} catch (NamingException | URISyntaxException e) {
						throw new RuntimeException("failed to get file-storages from DNS for " + dnsDomain, e);
					}
					
					for (String host : dnsStorages) {
						String[] domains = host.split("\\.");
						String id = domains[0];
						String url = protocol + host + ":" + port; 
						logger.debug("file-storage id " + id + ", url " + url + " found from DNS ");
						storageUrls.put(id, url);
					}
				}
			}
			
			Set<Service> serviceLocatorStorages = serviceLocator.getInternalServices(Role.FILE_STORAGE);
			
			for (Service storage : serviceLocatorStorages) {
				String id = storage.getRole();
				String url = storage.getUri();
				if (verbose) {
					logger.info("file-storage '" + id + "', url " + url + " found from service-locator");
				}
				storageUrls.put(id, url);
			}
			
			if (storageForNull != null && !storageForNull.isEmpty()) {
				if (storageUrls.containsKey(storageForNull)) {				
					if (verbose) {
						logger.info("use file-storage '" + storageForNull + "' if storage is null in the DB");
					}
					storageUrls.put(null, storageUrls.get(storageForNull));
					
					// storageForNull is only for migration, no need to write there
					readOnlyStorages.add(null);
				} else {
					throw new IllegalStateException(FILE_BROKER_STORAGE_NULL + " configured to '" + storageForNull
							+ "' but no such file-storage was found");
				}
			}
			
			for (String id : storageUrls.keySet()) {
				FileStorageClient client = new FileStorageClient(storageUrls.get(id), authService.getCredentials());
				client.setId(id);
				this.storageClients.put(id, client);
				
				if (!readOnlyStorages.contains(id)) {
					writeStorages.add(id);
				}
				
				if (!oldStorageClients.containsKey(id)) {
					logger.info("added file-storage '" + id + "', url " + client.getUri()
							 + (readOnlyStorages.contains(id) ? " (read-only)" : ""));
				}
			}
			
			for (String id : oldStorageClients.keySet()) {
				if (!storageClients.containsKey(id)) {
					logger.warn("lost file-storage '" + id + "'");
				}
			}
			
			this.fileStoragesLastUpdated = Instant.now();
		}
	}

	@GET
	@Path("sessions/{sessionId}/datasets/{datasetId}")
	public Response getDataset(
			@PathParam("sessionId") UUID sessionId, 
			@PathParam("datasetId") UUID datasetId,
			@QueryParam("download") String downloadParam,
			@QueryParam("type") String typeParam,
			@HeaderParam(HEADER_RANGE) String range,
			@Context SecurityContext sc) {
		
		// get query parameters
		// empty string when set, otherwise null (boolean without value would have been false)
		boolean download = downloadParam != null;
		boolean type = typeParam != null;
		
		String userToken = ((AuthPrincipal)sc.getUserPrincipal()).getTokenKey();

		Dataset dataset; 
		
		// checks authorization
		try {
			dataset = getDatasetObject(sessionId, datasetId, userToken, false);
		} catch (RestException e) {
			throw extractRestException(e);
		}

		if (dataset.getFile() == null || dataset.getFile().getFileId() == null) {
			throw new NotFoundException("file id is null");
		}

		UUID fileId = dataset.getFile().getFileId();
		String storageId = dataset.getFile().getStorage();
		
		FileStorageClient storageClient = getStorageClient(storageId);

		InputStream fileStream;
		try {
			fileStream = storageClient.download(fileId, range);
		} catch (RestException e) {
			throw extractRestException(e);
		}
		
		ResponseBuilder response = Response.ok(fileStream);
		
		if (download) {
			// hint filename for dataset export
			RestUtils.configureForDownload(response, dataset.getName());
		}

		if (type) {
			// rendenring a html file in an iFrame requires the Content-Type header
			response.type(getType(dataset).toString());
		} else {
			/* HTTP messages should contain content-type. but it's not required. The old servlet implementation
			 * didn't set it and the browsers were guessing it fine. I didn't find a way to remove the header in
			 * Jersey, but the wildcard content-type seems to cause the same end result.  
			 */
			response.type(MediaType.WILDCARD_TYPE);
		}
		
		return response.build();
	}
	
	private FileStorageClient getStorageClient(String storageId) {
		
		synchronized (storageClients) {
			
			FileStorageClient storageClient = storageClients.get(storageId);
			
			if (storageClient == null) {
				
				logger.info("storageId " + storageId + " found from DB but we don't know its URL. Trying to update file-storages again");
				// maybe the file-storage wasn't running when we searched last time and thus not in the DNS
				this.updateFileStorages(true);
				storageClient = storageClients.get(storageId);
				
				if (storageClient == null) {
					throw new InternalServerErrorException("storageId " + storageId + " is not found");
				}
			}
			
			return storageClient;
		}
	}
	
	private FileStorageClient getStorageClientForNewFile() {
		
		synchronized (storageClients) {
			
			if (writeStorages.isEmpty()) {
				logger.info("file upload requested, but there aren't any writable file-storages. Try to find again");
				this.updateFileStorages(true);
			}
			
			if (writeStorages.isEmpty()) {
				throw new InternalServerErrorException("no writable file-storage");
			}
			
			// choose one of the current storages			
			String storageId = writeStorages.get(rand.nextInt(writeStorages.size()));
			
			// make sure we will notice new replicas from DNS eventually
			this.updateInBackgroundIfNecessary();
						
			return storageClients.get(storageId);
		}
	}

	private void updateInBackgroundIfNecessary() {
		boolean isOld = Duration.between(fileStoragesLastUpdated, Instant.now())
				.compareTo(Duration.ofMinutes(1)) > 0;
				
		if (!dnsDomains.isEmpty() && fileStoragesLastUpdated == null || isOld) {			
			this.updateExecutor.execute(() -> {
				this.updateFileStorages(false);
				logger.info("file-storages updated: " + storageClients.size());
			});
		}
	}

	@PUT
	@Path("sessions/{sessionId}/datasets/{datasetId}")
	public Response putDataset(
			@PathParam("sessionId") UUID sessionId, 
			@PathParam("datasetId") UUID datasetId,
			InputStream fileStream,
			@QueryParam(FLOW_CHUNK_NUMBER) Long chunkNumber,
			@QueryParam(FLOW_CHUNK_SIZE) Long chunkSize,
			@QueryParam(FLOW_TOTAL_CHUNKS) Long flowTotalChunks,
			@Context SecurityContext sc) {
		
		String userToken = ((AuthPrincipal)sc.getUserPrincipal()).getTokenKey();

		Map<String, String> queryParams = new HashMap<>();
		
		if (chunkNumber != null) {
			queryParams.put(FLOW_CHUNK_NUMBER, "" + chunkNumber);
		}
		if (chunkSize != null) {
			queryParams.put(FLOW_CHUNK_SIZE, "" + chunkSize);
		}
		if (flowTotalChunks != null) {
			queryParams.put(FLOW_TOTAL_CHUNKS, "" + flowTotalChunks);
		}
		
		// checks authorization
		Dataset dataset;
		try {
			dataset = getDatasetObject(sessionId, datasetId, userToken, true);
		} catch (RestException e) {
			throw extractRestException(e);
		}
		
		FileStorageClient storageClient = null;
		
		if (dataset.getFile() == null) {
			storageClient = getStorageClientForNewFile();
			File file = new File();
			// create a new fileId
			file.setFileId(RestUtils.createUUID());
			file.setFileCreated(Instant.now());
			file.setStorage(storageClient.getId());
			dataset.setFile(file);
		} else {
			
			String storageId = dataset.getFile().getStorage();
			storageClient = getStorageClient(storageId);
		}
		
		long fileLength = storageClient.upload(dataset.getFile().getFileId(), fileStream, queryParams);

		if (fileLength >= 0) {
			// update the file size after each chunk
			dataset.getFile().setSize(fileLength);
		
			try {
				logger.debug("curl -X PUT " + sessionDbUri + "/sessions/" + sessionId + "/datasets/" + datasetId);
				this.sessionDbWithFileBrokerCredentials.updateDataset(sessionId, dataset);
				return Response.noContent().build();
				
			} catch (RestException e) {
				throw extractRestException(e);
			}
		} else {
			// upload paused
			return Response.serverError().build();
		}
	}
	
	private Dataset getDatasetObject(UUID sessionId, UUID datasetId, String userToken, boolean requireReadWrite) throws RestException {

		// check authorization
		SessionDbClient sessionDbWithUserCredentials = new SessionDbClient(sessionDbUri, sessionDbEventsUri,
				new StaticCredentials("token", userToken));
		
		logger.debug("curl --user token:" + userToken + " " + sessionDbUri + "/sessions/" + sessionId + "/datasets/" + datasetId + "?" + SessionDatasetResource.QUERY_PARAM_READ_WRITE + "=" + requireReadWrite);
		Dataset dataset = sessionDbWithUserCredentials.getDataset(sessionId, datasetId, requireReadWrite);

		if (dataset == null) {
			throw new ForbiddenException("dataset not found");
		}

		return dataset;
	}

	private WebApplicationException extractRestException(RestException e) {
		int statusCode = e.getResponse().getStatus();
		String msg = e.getMessage();
		if (statusCode == HttpServletResponse.SC_FORBIDDEN) {
			return new ForbiddenException(msg);
		} else if (statusCode == HttpServletResponse.SC_UNAUTHORIZED) {
			return new NotAuthorizedException(msg);
		} else if (statusCode == HttpServletResponse.SC_NOT_FOUND) {
			return new NotFoundException(msg);
		} else {
			return new InternalServerErrorException(e);
		}
	}

	private MediaType getType(Dataset dataset) {
		MediaType type = null;
		// we should store the recognized type so that client could rely on it
		if (dataset.getName().toLowerCase().endsWith(".html")) {
			// required for visualizing html files in an iFrame
			type = MediaType.TEXT_HTML_TYPE;
		}
		return type;
	}
}