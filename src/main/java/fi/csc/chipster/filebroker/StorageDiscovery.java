package fi.csc.chipster.filebroker;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.naming.NamingException;
import javax.ws.rs.InternalServerErrorException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filestorage.FileStorageClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.servicelocator.resource.Service;

public class StorageDiscovery {
	
	private static Logger logger = LogManager.getLogger();
	
	private static final String FILE_BROKER_STORAGE_READ_ONLY_PREFIX = "file-broker-storage-read-only-";
	private static final String FILE_BROKER_STORAGE_DNS_DOMAIN_PREFIX = "file-broker-storage-dns-domain-";
	private static final String FILE_BROKER_STORAGE_DNS_PROTOCOL = "file-broker-storage-dns-protocol";
	private static final String FILE_BROKER_STORAGE_DNS_PROTOCOL_ADMIN = "file-broker-storage-dns-protocol-admin";
	private static final String FILE_BROKER_STORAGE_DNS_PORT = "file-broker-storage-dns-port";
	private static final String FILE_BROKER_STORAGE_DNS_PORT_ADMIN = "file-broker-storage-dns-port-admin";
	private static final String FILE_BROKER_STORAGE_NULL = "file-broker-storage-null";
	
	private Map<String, Storage> storages = new HashMap<>();
	
	Random rand = new Random();
	private AuthenticationClient authService;
	private ExecutorService updateExecutor;
	private Instant fileStoragesLastUpdated;
	private Map<String, String> dnsDomains;
	private String storageForNull;
	private Collection<String> readOnlyStorages;
	private ServiceLocatorClient serviceLocator;
	private Config config;
	
	public StorageDiscovery(ServiceLocatorClient serviceLocator, AuthenticationClient authService, Config config) {
	
		
		this.serviceLocator = serviceLocator;
		this.authService = authService;
		this.config = config;
		
		dnsDomains = config.getConfigEntries(FILE_BROKER_STORAGE_DNS_DOMAIN_PREFIX);
		storageForNull = config.getString(FILE_BROKER_STORAGE_NULL);
		// create a new Set to be able to add new items
		readOnlyStorages = new HashSet<>(config.getConfigEntries(FILE_BROKER_STORAGE_READ_ONLY_PREFIX).values());
		
		this.updateFileStorages(true);
		
		this.updateExecutor = Executors.newCachedThreadPool();
	}
	
	private Map<String, Storage> getWriteStorages() {
		
		synchronized (storages) {
			return storages.keySet().stream()
				.filter(key -> !storages.get(key).isReadOnly())
				.collect(Collectors.toMap(key -> key, key -> storages.get(key)));
		}
	}
	
	public ArrayList<String> getStoragesForNewFile() {
		
		synchronized (storages) {

			Map<String, Storage> writeStorages = getWriteStorages();
			
			if (writeStorages.isEmpty()) {
				logger.info("file upload requested, but there aren't any writable file-storages. Try to find again");
				this.updateFileStorages(true);
				writeStorages = getWriteStorages();
			}
			
			
			if (writeStorages.isEmpty()) {
				throw new InternalServerErrorException("no writable file-storage");
			}

			// make sure we will notice new replicas from DNS eventually
			this.updateInBackgroundIfNecessary();
			
			// return storages in random order			
			ArrayList<String> storageIds = new ArrayList<String>(writeStorages.keySet());
			Collections.shuffle(storageIds);
			return storageIds;
		}
	}

	public FileStorageClient getStorageClient(String storageId) {
		
		Storage storage = storages.get(storageId);
		
		if (storage == null) {
			throw new InternalServerErrorException("storageId " + storageId + " is not found");
		}
		
		return new FileStorageClient(storage.getUri().toString(), authService.getCredentials());
	}
	
	public FileStorageClient getStorageClientForExistingFile(String storageId) {
		
		synchronized (storages) {
			
			if (!storages.containsKey(storageId)) {
				
				logger.info("storageId " + storageId + " found from DB but we don't know its URL. Trying to update file-storages again");
				// maybe the file-storage wasn't running when we searched last time and thus not in the DNS
				this.updateFileStorages(true);
			}
			
			return getStorageClient(storageId);
		}
	}

	private void updateFileStorages(boolean verbose) {
			
		HashMap<String, Storage> newStorages = new HashMap<>();
		HashMap<String, Storage> oldStorages = null;
		
		// this is used only for log messages, doesn't matter if it changes between this and when it's replaced with the new map
		synchronized (storages) {
			oldStorages = new HashMap<>(storages);
		}		
		
		for (String dnsConfigKey : dnsDomains.keySet()) {
			
			String dnsDomain = dnsDomains.get(dnsConfigKey);
			// use default or allow to be configured for each dns domain
			String protocol = config.getString(FILE_BROKER_STORAGE_DNS_PROTOCOL, dnsConfigKey);
			String protocolAdmin = config.getString(FILE_BROKER_STORAGE_DNS_PROTOCOL_ADMIN, dnsConfigKey);
			String port = config.getString(FILE_BROKER_STORAGE_DNS_PORT, dnsConfigKey);
			String adminPort = config.getString(FILE_BROKER_STORAGE_DNS_PORT_ADMIN, dnsConfigKey);
			
			if (dnsDomain != null && !dnsDomain.isEmpty()) {
				if (verbose) {
					logger.info("get file-storages from DNS record " + dnsDomain);
				}
				
				Set<String> dnsStorages = new HashSet<>();
				
				try {
					dnsStorages = DnsUtils.getSrvRecords(dnsDomain);
				} catch (NamingException | URISyntaxException e) {
					throw new RuntimeException("failed to get file-storages from DNS for " + dnsDomain, e);
				}
				
				for (String host : dnsStorages) {
					String[] domains = host.split("\\.");
					
					String id = domains[0];
					URI uri;
					try {
						uri = new URI(protocol + host + ":" + port);
						
						URI adminUri = new URI(protocolAdmin + host + ":" + adminPort); 
						boolean readOnly = readOnlyStorages.contains(id);
						logger.debug("file-storage id " + id + ", url " + uri + " found from DNS ");
						newStorages.put(id, new Storage(id, uri, adminUri, readOnly));
						
					} catch (URISyntaxException e) {
						logger.error("storage url error: " + id, e);
					} 
				}
			}
		}
		
		Set<Service> serviceLocatorStorages = serviceLocator.getInternalServices(Role.FILE_STORAGE);
		
		for (Service storage : serviceLocatorStorages) {
			String id = storage.getRole();
			try {
				URI uri = new URI(storage.getUri());
				
				URI adminUri = null;
				
				if (storage.getAdminUri() != null) {
					adminUri = new URI(storage.getAdminUri());
				}
				
				boolean readOnly = readOnlyStorages.contains(id);
							
				if (verbose) {
					logger.info("file-storage '" + id + "', url " + uri + " found from service-locator");
				}
				newStorages.put(id, new Storage(id, uri, adminUri, readOnly));
				
			} catch (URISyntaxException e) {
				logger.error("storage url error: " + id, e);
			} 
		}
		
		if (storageForNull != null && !storageForNull.isEmpty()) {
			if (newStorages.containsKey(storageForNull)) {				
				if (verbose) {
					logger.info("use file-storage '" + storageForNull + "' if storage is null in the DB");
				}
				// create copy
				Storage storage = new Storage(newStorages.get(storageForNull));					
				
				storage.setStorageId(null);
				// storageForNull is only for migration, no need to write there
				storage.setReadOnly(true);
				
				newStorages.put(null, storage);
				
			} else {
				throw new IllegalStateException(FILE_BROKER_STORAGE_NULL + " configured to '" + storageForNull
						+ "' but no such file-storage was found");
			}
		}
		
		// print only changes
		for (Storage storage : newStorages.values()) {									
			if (!oldStorages.containsKey(storage.getStorageId())) {
				logger.info("added file-storage '" + storage.getStorageId()+ "', url " + storage.getUri()
						 + (storage.isReadOnly() ? " (read-only)" : ""));
			}
		}
		
		for (Storage storage : oldStorages.values()) {
			if (!newStorages.containsKey(storage.getStorageId())) {
				logger.warn("lost file-storage '" + storage.getStorageId()+ "', url " + storage.getUri()
				 + (storage.isReadOnly() ? " (read-only)" : ""));
			}
		}
		
		synchronized (storages) {
			
			this.storages = newStorages;
			this.fileStoragesLastUpdated = Instant.now();
		}
	}
	
	private void updateInBackgroundIfNecessary() {
		boolean isOld = Duration.between(fileStoragesLastUpdated, Instant.now())
				.compareTo(Duration.ofMinutes(1)) > 0;
				
		if (!dnsDomains.isEmpty() && fileStoragesLastUpdated == null || isOld) {			
			this.updateExecutor.execute(() -> {
				this.updateFileStorages(false);
				logger.info("file-storages updated: " + storages.size());
			});
		}
	}
	
	public Map<String, Storage> getStorages() {
		synchronized (storages) {
			return storages;
		}
	}
}
