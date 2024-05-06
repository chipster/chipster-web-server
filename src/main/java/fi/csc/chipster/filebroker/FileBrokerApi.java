package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.ResetException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filestorage.UploadCancelledException;
import fi.csc.chipster.filestorage.client.FileStorageClient;
import fi.csc.chipster.filestorage.client.FileStorageDiscovery;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServletUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.exception.ConflictException;
import fi.csc.chipster.rest.exception.InsufficientStorageException;
import fi.csc.chipster.s3storage.FileLengthException;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import fi.csc.chipster.s3storage.client.S3StorageClient.ByteRange;
import fi.csc.chipster.s3storage.client.S3StorageClient.ChipsterUpload;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;
import fi.csc.chipster.sessiondb.resource.SessionDatasetResource;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;

public class FileBrokerApi {

    private Logger logger = LogManager.getLogger();

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

        InputStream fileStream;

        if (this.s3StorageClient.containsStorageId(storageId)) {

            ByteRange byteRange = s3StorageClient.parseByteRange(range);

            fileStream = s3StorageClient.downloadAndDecrypt(dataset.getFile(), byteRange);

        } else {

            FileStorageClient storageClient = this.fileStorageDiscovery.getStorageClientForExistingFile(storageId);

            try {
                fileStream = storageClient.download(dataset.getFile(), range);
            } catch (RestException e) {
                throw ServletUtils.extractRestException(e);
            }
        }

        return fileStream;
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

            if (this.s3StorageClient.containsStorageId(file.getStorage())) {

                if (file.getState() == FileState.UPLOADING) {

                    /*
                     * Restart paused upload
                     * 
                     * User probably has paused an upload and now continues it.
                     * This is fine for us, because we use S3 only for one-part uploads,
                     * so the app will upload the whole file again.
                     * 
                     * This doesn't save anything in the network transfers, but now the UI
                     * doesn't need to know that S3 doesn't support pause and continue.
                     */

                    logger.info("upload restarted");

                } else {

                    // don't allow changes to complete files
                    throw new ConflictException("file exists already");
                }
            } else {

                logger.info("PUT append to existing file in storage '" + file.getStorage() + "', chunk: " + chunkNumber
                        + " / " + flowTotalChunks + ", current size:  "
                        + FileBrokerAdminResource.humanFriendly(file.getSize())
                        + " / " + FileBrokerAdminResource.humanFriendly(flowTotalSize));
            }
        }

        if (this.s3StorageClient.containsStorageId(file.getStorage())) {

            logger.debug("upload to S3 bucket " + this.s3StorageClient.storageIdToBucket(file.getStorage()));

            // checksum is not available
            try {
                ChipsterUpload upload = this.s3StorageClient.encryptAndUpload(file.getFileId(),
                        fileStream, flowTotalSize, file.getStorage(), null);

                file.setSize(upload.getFileLength());
                file.setChecksum(upload.getChecksum());
                file.setEncryptionKey(upload.getEncryptionKey());
                file.setState(FileState.COMPLETE);

                // let's report only errors to keep file-broker logs cleaner, for example when
                // extracting a zip-session
                logger.debug("PUT file completed, file size " + FileBrokerAdminResource.humanFriendly(file.getSize()));

            } catch (ResetException e) {
                logger.warn("upload cancelled", e.getClass());

                try {
                    dataset.setFile(null);
                    this.sessionDbWithFileBrokerCredentials.updateDataset(sessionId, dataset);
                    this.sessionDbAdminClient.deleteFile(file.getFileId());
                } catch (RestException e1) {
                    logger.error("failed to delete File from DB", e1);
                }

                throw new BadRequestException("upload cancelled");
            }

        } else {

            // not synchronized, may fail when storage is lost
            FileStorageClient storageClient = this.fileStorageDiscovery.getStorageClient(file.getStorage());

            // update the file size after each chunk
            file.setSize(storageClient.upload(dataset.getFile().getFileId(), fileStream, chunkNumber, chunkSize,
                    flowTotalChunks, flowTotalSize));

            // update File state
            if (flowTotalSize == null) {
                logger.warn("flowTotalSize is not available, will assume the file is completed");
                file.setState(FileState.COMPLETE);

            } else if (file.getSize() == flowTotalSize) {
                logger.info("PUT file completed, file size " + FileBrokerAdminResource.humanFriendly(file.getSize()));
                file.setState(FileState.COMPLETE);

            } else {
                logger.info("PUT chunk completed: " + chunkNumber
                        + " / " + flowTotalChunks + ", current size:  "
                        + FileBrokerAdminResource.humanFriendly(file.getSize())
                        + " / " + FileBrokerAdminResource.humanFriendly(flowTotalSize));

                file.setState(FileState.UPLOADING);
            }
        }

        if (file.getSize() < 0) {
            // upload paused
            throw new UploadCancelledException("upload paused");
            // return Response.serverError().build();
        }

        try {
            this.sessionDbWithFileBrokerCredentials.updateDataset(sessionId, dataset);

        } catch (RestException e) {
            throw ServletUtils.extractRestException(e);
        }

        // move the file to s3-storage, if the file is complete, S3 is enabled and the
        // file is in file-storage

        // convert Boolean to boolean
        boolean isTemporary = temporary != null && temporary;

        if (file.getState() == FileState.COMPLETE
                && this.s3StorageClient.isEnabledForNewFiles()
                && !this.s3StorageClient.containsStorageId(file.getStorage())) {

            if (isTemporary) {
                logger.info("temporary file, do not move it");

            } else {
                String newStorageId = this.s3StorageClient.getStorageIdForNewFile();

                this.moveLater(file, newStorageId);
            }
        }
    }

    private String getStorage(Long chunkNumber, Long chunkSize, Long flowTotalChunks, Long flowTotalSize) {

        if (this.s3StorageClient.isEnabledForNewFiles() && this.s3StorageClient.isOnePartUpload(flowTotalChunks)) {

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

    MediaType getType(Dataset dataset) {
        MediaType type = null;
        // we should store the recognized type so that client could rely on it
        if (dataset.getName().toLowerCase().endsWith(".html")) {
            // required for visualizing html files in an iFrame
            type = MediaType.TEXT_HTML_TYPE;
        }
        return type;
    }

    public long move(File file, String targetStorageId, boolean ignoreSize) throws RestException, IOException {

        String sourceStorageId = file.getStorage();

        logger.info("move from '" + sourceStorageId + "' to '" + targetStorageId + "' fileId: " + file.getFileId()
                + ", " + FileBrokerAdminResource.humanFriendly(file.getSize()));

        InputStream sourceStream = null;

        if (this.s3StorageClient.containsStorageId(sourceStorageId)) {

            sourceStream = s3StorageClient.downloadAndDecrypt(file, null);

        } else {
            FileStorageClient sourceClient = fileStorageDiscovery
                    .getStorageClientForExistingFile(sourceStorageId);
            sourceStream = sourceClient.download(file, null);
        }

        long fileLength = -1;

        try {
            if (this.s3StorageClient.containsStorageId(targetStorageId)) {

                // Checksum can be null, if the file has newer been in s3. Then it's simply not
                // checked.
                ChipsterUpload upload = this.s3StorageClient.encryptAndUpload(file.getFileId(),
                        sourceStream, file.getSize(), targetStorageId, file.getChecksum());

                fileLength = upload.getFileLength();
                file.setChecksum(upload.getChecksum());
                file.setEncryptionKey(upload.getEncryptionKey());

            } else {

                FileStorageClient targetClient = fileStorageDiscovery.getStorageClient(targetStorageId);
                fileLength = targetClient.upload(file.getFileId(), sourceStream, null, null, null, file.getSize());
            }
        } catch (FileLengthException e) {
            if (ignoreSize) {
                logger.warn("ignore wrong file length when moving from " + sourceStorageId + " to " + targetStorageId,
                        e.getMessage());
            } else {
                throw e;
            }
        }

        file.setSize(fileLength);
        file.setStorage(targetStorageId);

        /*
         * This won't send events, but that shouldn't matter, because the file-broker
         * will get the storage from the session-db anyway.
         */
        this.sessionDbAdminClient.updateFile(file);

        if (this.s3StorageClient.containsStorageId(sourceStorageId)) {

            this.s3StorageClient.delete(sourceStorageId, file.getFileId());

        } else {
            fileStorageDiscovery.getStorageClient(sourceStorageId).delete(file.getFileId());
        }

        return file.getSize();

    }

    public void moveLater(File file, String targetStorageId) {
        this.fileMoverExecutor.submit(new Runnable() {
            public void run() {
                try {

                    String sourceStorage = file.getStorage();
                    long t = System.currentTimeMillis();

                    move(file, targetStorageId, false);

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
}
