package fi.csc.chipster.filebroker;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.filestorage.client.FileStorageClient;
import fi.csc.chipster.filestorage.client.FileStorageDiscovery;
import fi.csc.chipster.s3storage.FileLengthException;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import fi.csc.chipster.s3storage.client.S3StorageClient.ChipsterUpload;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.model.File;

public class FileBrokerApi {

    private Logger logger = LogManager.getLogger();

    private S3StorageClient s3StorageClient;
    private FileStorageDiscovery fileStorageDiscovery;
    private ExecutorService fileMoverExecutor;

    private SessionDbAdminClient sessionDbAdminClient;

    public FileBrokerApi(S3StorageClient s3StorageClient, FileStorageDiscovery storageDiscovery,
            SessionDbAdminClient sessionDbAdminClient) {

        this.s3StorageClient = s3StorageClient;
        this.fileStorageDiscovery = storageDiscovery;
        this.sessionDbAdminClient = sessionDbAdminClient;
        this.fileMoverExecutor = Executors.newFixedThreadPool(1);
    }

    public long move(File file, String targetStorageId, boolean ignoreSize) throws RestException {

        String sourceStorageId = file.getStorage();

        logger.info("move from '" + sourceStorageId + "' to '" + targetStorageId + "' fileId: " + file.getFileId()
                + ", " + FileBrokerAdminResource.humanFriendly(file.getSize()));

        InputStream sourceStream = null;

        if (this.s3StorageClient.containsStorageId(sourceStorageId)) {

            sourceStream = s3StorageClient.downloadAndDecrypt(file, null);

        } else {
            FileStorageClient sourceClient = fileStorageDiscovery
                    .getStorageClientForExistingFile(sourceStorageId);
            sourceStream = sourceClient.download(file.getFileId(), null);
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

                Map<String, String> queryParams = new HashMap<>() {
                    {
                        put(FileBrokerResource.QP_FLOW_TOTAL_SIZE, "" + file.getSize());
                    }
                };

                FileStorageClient targetClient = fileStorageDiscovery.getStorageClient(targetStorageId);
                fileLength = targetClient.upload(file.getFileId(), sourceStream, queryParams, file.getSize());
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
