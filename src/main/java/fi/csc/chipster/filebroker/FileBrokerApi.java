package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filestorage.client.FileStorageClient;
import fi.csc.chipster.filestorage.client.FileStorageDiscovery;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServletUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.exception.InsufficientStorageException;
import fi.csc.chipster.s3storage.checksum.ChecksumException;
import fi.csc.chipster.s3storage.checksum.FileLengthException;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;
import fi.csc.chipster.sessiondb.model.MetadataFile;
import fi.csc.chipster.sessiondb.resource.SessionDatasetResource;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;

/**
 * file-broker API
 * 
 * Class for file-broker functionality that is needed in
 * FileBrokerResource(Servlet). Partly used also in FileBrokerAdminResource.
 */
public class FileBrokerApi {

    private Logger logger = LogManager.getLogger();

    public static final String MF_DELETE_AFTER_DOWNLOAD = "delete-after-download";

    private S3StorageClient s3StorageClient;
    private FileStorageDiscovery fileStorageDiscovery;
    private ExecutorService fileMoverExecutor;

    private SessionDbAdminClient sessionDbAdminClient;

    private SessionDbClient sessionDbWithFileBrokerCredentials;

    private String sessionDbUri;

    public FileBrokerApi(S3StorageClient s3StorageClient, FileStorageDiscovery storageDiscovery,
            SessionDbAdminClient sessionDbAdminClient, SessionDbClient sessionDbClient,
            ServiceLocatorClient serviceLocator) {

        this.s3StorageClient = s3StorageClient;
        this.fileStorageDiscovery = storageDiscovery;
        this.sessionDbWithFileBrokerCredentials = sessionDbClient;
        this.sessionDbAdminClient = sessionDbAdminClient;
        this.fileMoverExecutor = Executors.newFixedThreadPool(1);
        this.sessionDbUri = serviceLocator.getInternalService(Role.SESSION_DB).getUri();
    }

    public StorageClient getStorageClient(String storageId, boolean fileShouldExist) {

        if (this.s3StorageClient.containsStorageId(storageId)) {

            return s3StorageClient;

        } else {

            if (fileShouldExist) {
                return this.fileStorageDiscovery.getStorageClientForExistingFile(storageId);
            }

            // not synchronized, may fail when storage is lost
            return this.fileStorageDiscovery.getStorageClient(storageId);
        }
    }

    public InputStream getDataset(Dataset dataset,
            String range, String userToken) throws IOException {

        if (dataset.getFile() == null || dataset.getFile().getFileId() == null) {
            throw new NotFoundException("file id is null");
        }

        String storageId = dataset.getFile().getStorage();

        if (range != null && !range.isEmpty()) {

            logger.info("GET from storage '" + storageId + "', " + range + ", file size: "
                    + FileBrokerAdminResource.humanFriendly(dataset.getFile().getSize()));
        } else {
            logger.info("GET from storage '" + storageId + "', "
                    + FileBrokerAdminResource.humanFriendly(dataset.getFile().getSize()));
        }

        return this.getStorageClient(storageId, true).download(dataset.getFile(), range);
    }

    public void putDataset(UUID sessionId, UUID datasetId, InputStream fileStream, Long chunkNumber, Long chunkSize,
            Long flowTotalChunks, Long flowTotalSize, Boolean temporary, String userToken) {

        logger.debug("chunkNumber: " + chunkNumber);
        logger.debug("chunkSize: " + chunkSize);
        logger.debug("flowTotalChunks: " + flowTotalChunks);
        logger.debug("flowTotalSize: " + flowTotalSize);

        // checks authorization
        Dataset dataset;
        try {
            dataset = getDatasetObject(sessionId, datasetId, userToken, true);
        } catch (RestException e) {
            throw ServletUtils.extractRestException(e);
        }

        File file;

        if (dataset.getFile() == null) {

            // find storage for new file

            logger.debug("PUT new file");

            UUID fileId = RestUtils.createUUID();
            String storageId = getStorage(chunkNumber, chunkSize, flowTotalChunks, flowTotalSize);
            Instant created = Instant.now();

            file = new File();
            // create a new fileId
            file.setFileId(fileId);
            file.setFileCreated(created);
            file.setStorage(storageId);
            file.setState(FileState.UPLOADING);

            dataset.setFile(file);

            logger.info("PUT new file to storage '" + file.getStorage() + "', chunk: " + chunkNumber + " / "
                    + flowTotalChunks + ", total size: " + FileBrokerAdminResource.humanFriendly(flowTotalSize));

            // Add the File to the DB before creating the file in storage. Otherwise storage
            // check could think the file in storage is orphan and delete it.
            try {
                this.sessionDbWithFileBrokerCredentials.updateDataset(sessionId, dataset);
            } catch (RestException e) {
                throw ServletUtils.extractRestException(e);
            }

        } else {

            // find the storage of existing file for appending

            file = dataset.getFile();

            this.getStorageClient(file.getStorage(), true).checkIfAppendAllowed(file, chunkNumber, chunkSize,
                    flowTotalChunks, flowTotalSize);
        }

        StorageClient storageClient = this.getStorageClient(file.getStorage(), false);

        try {
            file = storageClient.upload(file, fileStream, chunkNumber, chunkSize,
                    flowTotalChunks, flowTotalSize);
            dataset.setFile(file);
        } catch (RestException e) {
            if (storageClient.deleteAfterUploadException()) {
                logger.warn("upload cancelled", e.getClass());

                try {
                    dataset.setFile(null);
                    this.sessionDbWithFileBrokerCredentials.updateDataset(sessionId, dataset);
                    this.sessionDbAdminClient.deleteFile(file.getFileId());
                } catch (RestException e1) {
                    logger.error("failed to delete File from DB", e1);
                }

            }
            throw new BadRequestException("upload cancelled");
        }

        try {
            this.sessionDbWithFileBrokerCredentials.updateDataset(sessionId, dataset);

        } catch (RestException e) {
            throw ServletUtils.extractRestException(e);
        }

        // convert Boolean to boolean
        boolean isTemporary = temporary != null && temporary;

        if (isTemporary) {
            logger.info("temporary file, do not move it");
        } else {
            moveAfterUpload(file);
        }
    }

    /**
     * Move the file to s3-storage, if the file is complete, S3 is enabled and the
     * file is in file-storage
     * 
     * @param file
     */
    private void moveAfterUpload(File file) {
        if (file.getState() != FileState.COMPLETE) {
            logger.info("do not move the file yet because state is " + file.getState());
            return;
        } else if (!this.s3StorageClient.isEnabledForNewFiles()) {
            logger.info("do not move the file, because S3 storage is not enabled for new files");
            return;
        } else if (this.s3StorageClient.containsStorageId(file.getStorage())) {
            logger.info("no need to move the file, because it is already in S3: " + file.getStorage());
            return;
        }

        logger.info("going to move the file");
        String newStorageId = this.s3StorageClient.getStorageIdForNewFile();

        this.moveLater(file, newStorageId);
    }

    private String getStorage(Long chunkNumber, Long chunkSize, Long flowTotalChunks, Long flowTotalSize) {

        if (flowTotalSize == null) {
            logger.info("total size is null, will use file-storage");
        }

        if (flowTotalSize != null && this.s3StorageClient.isEnabledForNewFiles()
                && this.s3StorageClient.isOnePartUpload(flowTotalChunks)) {

            return this.s3StorageClient.getStorageIdForNewFile();

        } else {

            for (String storageId : this.fileStorageDiscovery.getStoragesForNewFile()) {
                // not synchronized, may fail when storage is lost
                FileStorageClient storageClient = this.fileStorageDiscovery.getStorageClient(storageId);

                try {
                    storageClient.checkIfUploadAllowed(chunkNumber, chunkSize, flowTotalChunks, flowTotalSize);

                    return storageId;

                } catch (InsufficientStorageException e) {
                    logger.warn("insufficient storage in storageId '" + storageId + "', trying others");
                } catch (RestException e) {
                    throw ServletUtils.extractRestException(e);
                }
            }

            logger.error("insufficient storage on all storages");
            throw new InsufficientStorageException("insufficient storage on all storages");
        }
    }

    Dataset getDatasetObject(UUID sessionId, UUID datasetId, String userToken, boolean requireReadWrite)
            throws RestException {

        // check authorization
        SessionDbClient sessionDbWithUserCredentials = new SessionDbClient(sessionDbUri, null,
                new StaticCredentials("token", userToken));

        logger.debug("curl --user token:" + userToken + " " + sessionDbUri + "/sessions/" + sessionId + "/datasets/"
                + datasetId + "?" + SessionDatasetResource.QUERY_PARAM_READ_WRITE + "=" + requireReadWrite);
        Dataset dataset = sessionDbWithUserCredentials.getDataset(sessionId, datasetId, requireReadWrite);

        if (dataset == null) {
            throw new ForbiddenException("dataset not found");
        }

        return dataset;
    }

    String getType(Dataset dataset) {
        String type = null;
        // we should store the recognized type so that client could rely on it
        if (dataset.getName().toLowerCase().endsWith(".html")) {
            // required for visualizing html files in an iFrame
            type = MediaType.TEXT_HTML_TYPE.toString();
        } else if (dataset.getName().toLowerCase().endsWith(".pdf")) {
            // required for visualizing html files in an iFrame
            type = "application/pdf";
        }
        return type;
    }

    public void move(File sourceFile, String targetStorageId, boolean continueDespiteWrongSize,
            boolean continueDespiteWrongChecksum) throws RestException, IOException {

        logger.info("move from '" + sourceFile.getStorage() + "' to '" + targetStorageId + "' fileId: "
                + sourceFile.getFileId()
                + ", " + FileBrokerAdminResource.humanFriendly(sourceFile.getSize()));

        StorageClient sourceClient = this.getStorageClient(sourceFile.getStorage(), true);
        InputStream sourceStream = sourceClient.download(sourceFile, null);

        File targetFile = null;

        try {

            targetFile = (File) sourceFile.clone();

            targetFile.setStorage(targetStorageId);

            StorageClient targetClient = this.getStorageClient(targetStorageId, false);

            targetFile = targetClient.upload(targetFile, sourceStream, null, null, null,
                    targetFile.getSize());

            /*
             * This won't send events, but that shouldn't matter, because the file-broker
             * will get the storage from the session-db anyway.
             */
            this.sessionDbAdminClient.updateFile(targetFile);

            sourceClient.delete(sourceFile);

        } catch (FileLengthException e) {
            if (continueDespiteWrongSize) {
                logger.warn(
                        "continue despite wrong file length when moving from " + sourceFile.getStorage() + " to "
                                + targetStorageId + "(" + e.getMessage() + ")");
            } else {
                throw e;
            }
        } catch (ChecksumException e) {
            if (continueDespiteWrongChecksum) {
                logger.warn(
                        "continue despite wrong file checksum when moving from " + sourceFile.getStorage() + " to "
                                + targetStorageId + "(" + e.getMessage() + ")");
            } else {
                throw e;
            }
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("clone not supported", e);
        }
    }

    public void moveLater(File file, String targetStorageId) {
        this.fileMoverExecutor.submit(new Runnable() {
            public void run() {
                try {

                    String sourceStorage = file.getStorage();
                    long t = System.currentTimeMillis();

                    move(file, targetStorageId, false, false);

                    long dt = (System.currentTimeMillis() - t);
                    String speed = FileBrokerAdminResource.humanFriendly(file.getSize() * 1000 / dt) + "/s";

                    logger.info("move from '" + sourceStorage + "' to '" + targetStorageId + "' fileId: "
                            + file.getFileId() + " done. " + speed);
                } catch (Exception e) {
                    logger.error("file move failed", e);
                }
            }
        });
    }

    public void afterDownload(Dataset dataset) {
        if (dataset.getMetadataFiles().stream()
                .map((MetadataFile mf) -> mf.getName())
                .anyMatch(name -> MF_DELETE_AFTER_DOWNLOAD.equals(name))) {

            logger.info("dataset is marked to be deleted after download");
            try {
                /*
                 * Special file which should be deleted after it's downloaded once
                 * 
                 * Let's use file-broker credentials to delete it, so that this works even if
                 * the downloading was done with read-only token.
                 * 
                 * This is an exception in the security model when a read-only token can change
                 * the session, but let's consider in a way that the real change happened when
                 * somebody marked the dataset with MF_DELETE_AFTER_DOWNLOAD with read-write
                 * rights and this is more an automated action happening after it.
                 */
                this.sessionDbWithFileBrokerCredentials.deleteDataset(dataset.getSessionId(), dataset.getDatasetId());
            } catch (RestException e) {
                logger.error("failed to delete dataset");
            }
        }
    }
}
