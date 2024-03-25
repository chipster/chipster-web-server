package fi.csc.chipster.filebroker.s3storageclient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.HeadBucketRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import fi.csc.chipster.filebroker.StorageUtils;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.model.File;
import io.jsonwebtoken.io.IOException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;

public class S3StorageAdminClient {

    private static final String OBJECT_KEY_ORPHAN_FILES = "chipster-orphan-files.json";

    private Logger logger = LogManager.getLogger();

    private S3StorageClient s3StorageClient;

    private SessionDbAdminClient sessionDbAdminClient;

    public S3StorageAdminClient(S3StorageClient s3StorageClient, SessionDbAdminClient sessionDbAdminClient) {
        this.s3StorageClient = s3StorageClient;
        this.sessionDbAdminClient = sessionDbAdminClient;
    }

    public String getFileStats(String storageId) {

        HashMap<String, Long> files = getFilesAndSizes(storageId);
        long fileBytes = files.values().stream()
                .mapToLong(l -> l).sum();

        HashMap<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("storageId", storageId);
        jsonMap.put("fileCount", files.size());
        jsonMap.put("fileBytes", fileBytes);
        jsonMap.put("status", null);

        return RestUtils.asJson(jsonMap);
    }

    private HashMap<String, Long> getFilesAndSizes(String storageId) throws IOException {

        HashMap<String, Long> result = new HashMap<>();

        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        ObjectListing objectListing = this.s3StorageClient.getTransferManager().getAmazonS3Client()
                .listObjects(bucket);

        do {
            List<S3ObjectSummary> summaries = objectListing.getObjectSummaries();

            for (S3ObjectSummary summary : summaries) {

                result.put(summary.getKey(), summary.getSize());
            }

            objectListing = this.s3StorageClient.getTransferManager().getAmazonS3Client()
                    .listNextBatchOfObjects(objectListing);

        } while (objectListing.isTruncated());

        // remove our list of orphan files
        result.remove(OBJECT_KEY_ORPHAN_FILES);

        return result;
    }

    public String getStatus(String storageId) {

        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        try {
            // check if bucket exists
            final HeadBucketRequest request = new HeadBucketRequest(bucket);
            this.s3StorageClient.getTransferManager().getAmazonS3Client().headBucket(request);

            HashMap<String, Object> jsonMap = new HashMap<>();
            // jsonMap.put("status", null);

            return RestUtils.asJson(jsonMap);

        } catch (AmazonServiceException e) {
            if (e.getStatusCode() == 404 | e.getStatusCode() == 403 || e.getStatusCode() == 301) {
                throw new NotFoundException();
            }
            throw e;
        } catch (Exception e) {
            logger.error("failed to check bucket status", e);
            throw e;
        }
    }

    public String getStorageId(String storageId) {

        HashMap<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("storageId", storageId);

        return RestUtils.asJson(jsonMap);
    }

    public void startCheck(String storageId) {
        logger.info("storage check started");

        try {
            // collect storage files first to make sure we don't delete new files
            Map<String, Long> storageFiles = getFilesAndSizes(storageId);
            Map<String, Long> oldOrphanFiles = getOldOrphanFiles(storageId, storageFiles);

            HashMap<String, Long> dbFilesMap = new HashMap<>();
            List<File> dbFiles = this.sessionDbAdminClient.getFiles(storageId);

            // convert to ciphertext sizes
            for (File dbFile : dbFiles) {
                Long plaintextSize = dbFile.getSize();
                long ciphertextSize = s3StorageClient.getFileEncryption().getEncryptedLength(plaintextSize);
                dbFilesMap.put(dbFile.getFileId().toString(), ciphertextSize);
            }

            List<String> orphanFiles = StorageUtils.check(storageFiles, oldOrphanFiles, dbFilesMap);

            saveListOfOrphanFiles(orphanFiles, storageId);

            logger.info(orphanFiles.size() + " orphan files saved in " + OBJECT_KEY_ORPHAN_FILES);

        } catch (RestException | java.io.IOException | InterruptedException e) {
            logger.error("storage check failed", e);
            throw new InternalServerErrorException("storage check failed");
        }
    }

    private void saveListOfOrphanFiles(List<String> orphanFiles, String storageId)
            throws InterruptedException, java.io.IOException {

        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        String json = RestUtils.asJson(orphanFiles);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8.name());

        try (InputStream is = new ByteArrayInputStream(jsonBytes)) {

            this.s3StorageClient.upload(bucket, is, OBJECT_KEY_ORPHAN_FILES, jsonBytes.length);
        }
    }

    private List<String> loadListOfOrphanFiles(String storageId) throws java.io.IOException, InterruptedException {

        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        try (S3ObjectInputStream is = this.s3StorageClient.download(bucket, OBJECT_KEY_ORPHAN_FILES, null, null)) {

            String json = IOUtils.toString(is, StandardCharsets.UTF_8.name());

            @SuppressWarnings("unchecked")
            List<String> orphanFiles = RestUtils.parseJson(List.class, String.class, json);

            return orphanFiles;
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() == 404) {
                // no old orphan files, because the file doesn't exist
                return new ArrayList<String>();
            }
            throw e;
        }
    }

    private Map<String, Long> getOldOrphanFiles(String storageId, Map<String, Long> storageFiles)
            throws java.io.IOException, InterruptedException {
        Set<String> fileNames = new HashSet<String>(this.loadListOfOrphanFiles(storageId));

        Map<String, Long> oldOrphansFiles = storageFiles.entrySet().stream()
                .filter(entry -> fileNames.contains(entry.getKey()))
                .collect(Collectors.toMap(
                        entry -> entry.getKey(), entry -> entry.getValue()));

        return oldOrphansFiles;
    }

    public void deleteOldOrphans(String storageId) {

        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        try {
            List<String> oldOrphans = loadListOfOrphanFiles(storageId);
            logger.info("delete " + oldOrphans.size() + " old orphan files");

            for (String orphan : oldOrphans) {
                logger.info("delete old orphan file " + orphan);
                this.s3StorageClient.getTransferManager().getAmazonS3Client().deleteObject(bucket, orphan);
            }

            logger.info("delete " + oldOrphans.size() + " old orphan files: done");

        } catch (java.io.IOException | InterruptedException e) {
            logger.error("failed to delete old orphan files", e);
            throw new InternalServerErrorException("failed to delete old orphan files");
        }
    }
}
