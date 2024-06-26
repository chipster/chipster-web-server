package fi.csc.chipster.s3storage.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.IllegalBlockSizeException;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.iterable.S3Objects;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.HeadBucketRequest;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import fi.csc.chipster.filebroker.StorageAdminClient;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.s3storage.checksum.ChecksumException;
import fi.csc.chipster.s3storage.checksum.FileLengthException;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;
import io.jsonwebtoken.io.IOException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;

/**
 * Admin client for s3-storage
 * 
 * This is used in file-broker to do admin operations for the S3. It
 * uses aws-sdk library to make rquests directly to the S3 API, without going
 * through the s3-storage component (which is needed only for file deletion).
 */
public class S3StorageAdminClient implements StorageAdminClient {

    private static final String OBJECT_KEY_ORPHAN_FILES = "chipster-orphan-files.json";

    private Logger logger = LogManager.getLogger();

    private S3StorageClient s3StorageClient;

    private SessionDbAdminClient sessionDbAdminClient;

    private String storageId;

    public S3StorageAdminClient(S3StorageClient s3StorageClient, SessionDbAdminClient sessionDbAdminClient,
            String storageId) {
        this.s3StorageClient = s3StorageClient;
        this.sessionDbAdminClient = sessionDbAdminClient;
        this.storageId = storageId;
    }

    public String getFileStats() {

        HashMap<String, Long> files = getFilesAndSizes();
        long fileBytes = files.values().stream()
                .mapToLong(l -> l).sum();

        HashMap<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("storageId", storageId);
        jsonMap.put("fileCount", files.size());
        jsonMap.put("fileBytes", fileBytes);
        jsonMap.put("status", null);

        return RestUtils.asJson(jsonMap);
    }

    private HashMap<String, Long> getFilesAndSizes() throws IOException {

        HashMap<String, Long> result = new HashMap<>();

        String s3Name = this.s3StorageClient.storageIdToS3Name(storageId);
        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        AmazonS3 amazonS3Client = this.s3StorageClient.getTransferManager(s3Name).getAmazonS3Client();

        S3Objects.inBucket(amazonS3Client, bucket).forEach((S3ObjectSummary summary) -> {
            result.put(summary.getKey(), summary.getSize());
        });
        // remove our list of orphan files
        result.remove(OBJECT_KEY_ORPHAN_FILES);

        return result;
    }

    public String getStatus() {

        String s3Name = this.s3StorageClient.storageIdToS3Name(storageId);
        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        try {
            // check if bucket exists
            final HeadBucketRequest request = new HeadBucketRequest(bucket);
            this.s3StorageClient.getTransferManager(s3Name).getAmazonS3Client().headBucket(request);

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

    public void startCheck(Long uploadMaxHours, Boolean deleteDatasetsOfMissingFiles,
            Boolean checksums) {
        logger.info(storageId + " storage check started");

        try {
            /*
             * Collect storage files first to make sure we don't delete new files
             * 
             * Files are added to the DB before they are created in storage. Then we know
             * that all files in storage are also in DB and cannot be considered orphan by
             * accident.
             */
            Map<String, Long> storageFiles = getFilesAndSizes();
            Map<String, Long> oldOrphanFiles = getOldOrphanFiles(storageFiles);

            List<File> completeDbFiles = this.sessionDbAdminClient.getFiles(storageId, FileState.COMPLETE);
            List<File> uploadingDbFiles = this.sessionDbAdminClient.getFiles(storageId, FileState.UPLOADING);

            Set<File> oldUploads = StorageAdminClient.deleteOldUploads(uploadingDbFiles, this.sessionDbAdminClient,
                    uploadMaxHours);

            uploadingDbFiles.removeAll(oldUploads);

            Map<String, File> completeDbFilesMap = getEncryptedLengthMap(completeDbFiles);
            Map<String, File> uploadingDbFilesMap = getEncryptedLengthMap(uploadingDbFiles);

            List<String> orphanFiles = StorageAdminClient.check(storageFiles, oldOrphanFiles, uploadingDbFilesMap,
                    completeDbFilesMap, deleteDatasetsOfMissingFiles, sessionDbAdminClient, storageId);

            saveListOfOrphanFiles(orphanFiles);

            logger.info(storageId + " " + orphanFiles.size() + " orphan files saved in " + OBJECT_KEY_ORPHAN_FILES);

            if (checksums != null && checksums) {
                verifyChecksums(storageId + " state COMPLETE,  ", completeDbFiles);
                verifyChecksums(storageId + " state UPLOADING, ", uploadingDbFiles);
            }

        } catch (RestException | java.io.IOException | InterruptedException | CloneNotSupportedException e) {
            logger.error("storage check failed", e);
            throw new InternalServerErrorException("storage check failed");
        }
    }

    private void verifyChecksums(String name, List<File> files) throws java.io.IOException {
        logger.info(name + "verify checksums");

        long okFiles = 0;
        long nullKeyFiles = 0;
        long wrongSize = 0;
        long wrongChecksum = 0;
        long missingFile = 0;
        long illegalBlockSize = 0;

        for (File file : files) {

            if (file.getEncryptionKey() == null) {
                nullKeyFiles++;

            } else {

                try (InputStream is = this.s3StorageClient.downloadAndDecrypt(file, null)) {
                    IOUtils.copyLarge(is, OutputStream.nullOutputStream());
                    okFiles++;
                } catch (FileLengthException e) {
                    logger.info("wrong file size: " + file.getFileId() + "(" + e.getMessage() + ")");
                    wrongSize++;

                } catch (ChecksumException e) {
                    logger.info("checksum failed: " + file.getFileId());
                    wrongChecksum++;
                } catch (AmazonS3Exception e) {
                    if (e.getStatusCode() == 404) {
                        logger.info("cannot verify checksum, object not found: " + file.getFileId());
                        missingFile++;
                    } else {
                        throw e;
                    }
                } catch (java.io.IOException e) {
                    if (e.getCause() instanceof IllegalBlockSizeException) {
                        logger.info("checksum failed: " + file.getFileId() + " " + e.getCause() + " " + e.getMessage());
                        illegalBlockSize++;
                    } else {
                        throw e;
                    }
                }
            }

            // show little bit of progress information
            // we may see this several times, when there are non-ok files
            if (okFiles % 100 == 0) {
                logger.info(name + "verified checksums: " + okFiles);
            }
        }

        logger.info(name + "verified checksums: " + okFiles);
        logger.info(name + "null key files:     " + nullKeyFiles);
        logger.info(name + "wrong file sizes:   " + wrongSize);
        logger.info(name + "wrong checksums:    " + wrongChecksum);
        logger.info(name + "missing files:      " + missingFile);
        logger.info(name + "illegel block size: " + illegalBlockSize);
    }

    private HashMap<String, File> getEncryptedLengthMap(List<File> dbFiles) throws CloneNotSupportedException {

        HashMap<String, File> s3FilesMap = new HashMap<>();

        // convert to ciphertext sizes
        for (File dbFile : dbFiles) {
            // don't modify the original dbFile, because the original plaintext size may be
            // needed in checksum verification
            File s3File = (File) dbFile.clone();
            Long plaintextSize = dbFile.getSize();
            long ciphertextSize = s3StorageClient.getFileEncryption().getEncryptedLength(plaintextSize);
            s3File.setSize(ciphertextSize);
            s3FilesMap.put(s3File.getFileId().toString(), s3File);
        }

        return s3FilesMap;
    }

    private void saveListOfOrphanFiles(List<String> orphanFiles)
            throws InterruptedException, java.io.IOException {

        String s3Name = this.s3StorageClient.storageIdToS3Name(storageId);
        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        String json = RestUtils.asJson(orphanFiles);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8.name());

        try (InputStream is = new ByteArrayInputStream(jsonBytes)) {

            this.s3StorageClient.upload(s3Name, bucket, is, OBJECT_KEY_ORPHAN_FILES, (long) jsonBytes.length);
        }
    }

    private List<String> loadListOfOrphanFiles() throws java.io.IOException, InterruptedException {

        String s3Name = this.s3StorageClient.storageIdToS3Name(storageId);
        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        try (S3ObjectInputStream is = this.s3StorageClient.download(s3Name, bucket, OBJECT_KEY_ORPHAN_FILES, null,
                null)) {

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

    private Map<String, Long> getOldOrphanFiles(Map<String, Long> storageFiles)
            throws java.io.IOException, InterruptedException {
        Set<String> fileNames = new HashSet<String>(this.loadListOfOrphanFiles());

        Map<String, Long> oldOrphansFiles = storageFiles.entrySet().stream()
                .filter(entry -> fileNames.contains(entry.getKey()))
                .collect(Collectors.toMap(
                        entry -> entry.getKey(), entry -> entry.getValue()));

        return oldOrphansFiles;
    }

    public void deleteOldOrphans() {

        String s3Name = this.s3StorageClient.storageIdToS3Name(storageId);
        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        try {
            List<String> oldOrphans = loadListOfOrphanFiles();
            logger.info("delete " + oldOrphans.size() + " old orphan files");

            for (String orphan : oldOrphans) {
                logger.info("delete old orphan file " + orphan);
                this.s3StorageClient.getTransferManager(s3Name).getAmazonS3Client().deleteObject(bucket, orphan);
            }

            logger.info("delete " + oldOrphans.size() + " old orphan files: done");

        } catch (java.io.IOException | InterruptedException e) {
            logger.error("failed to delete old orphan files", e);
            throw new InternalServerErrorException("failed to delete old orphan files");
        }
    }

    /**
     * s3-storage doesn't have separate replicas for each storageId, so file-broker
     * and s3-storage cannot disagree about the ID
     */
    @Override
    public String getStorageId() {

        HashMap<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("storageId", storageId);

        return RestUtils.asJson(jsonMap);
    }
}
