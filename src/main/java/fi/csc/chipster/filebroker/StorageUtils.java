package fi.csc.chipster.filebroker;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;
import io.jsonwebtoken.io.IOException;

public class StorageUtils {

        private static Logger logger = LogManager.getLogger();

        public static List<String> check(Map<String, Long> storageFiles, Map<String, Long> oldOrphanFiles,
                        Map<String, Long> uploadingDbFiles, Map<String, Long> completeDbFiles,
                        Boolean deleteDatasetsOfMissingFiles, SessionDbAdminClient sessionDbAdminClient)
                        throws RestException, IOException {

                checkNameSizeAndMissing(completeDbFiles, storageFiles, FileState.COMPLETE, deleteDatasetsOfMissingFiles,
                                sessionDbAdminClient);
                checkNameSizeAndMissing(uploadingDbFiles, storageFiles, FileState.UPLOADING,
                                deleteDatasetsOfMissingFiles, sessionDbAdminClient);

                List<String> orphanFiles = new HashSet<>(storageFiles.keySet()).stream()
                                .filter(fileName -> !completeDbFiles.containsKey(fileName)
                                                && !uploadingDbFiles.containsKey(fileName))
                                .collect(Collectors.toList());

                long orphanFilesTotal = orphanFiles.stream().mapToLong(storageFiles::get).sum();
                long oldOrphanFilesTotal = oldOrphanFiles.values().stream().mapToLong(s -> s).sum();

                logger.info(oldOrphanFiles.size() + " old orphan files ("
                                + FileUtils.byteCountToDisplaySize(oldOrphanFilesTotal) + ")");
                logger.info(orphanFiles.size() + " orphan files (" + FileUtils.byteCountToDisplaySize(orphanFilesTotal)
                                + ")");
                return orphanFiles;
        }

        private static void checkNameSizeAndMissing(Map<String, Long> dbFiles, Map<String, Long> storageFiles,
                        FileState state, Boolean deleteDatasetsOfMissingFiles,
                        SessionDbAdminClient sessionDbAdminClient) throws RestException {

                List<String> correctNameFiles = new HashSet<>(dbFiles.keySet()).stream()
                                .filter(fileName -> storageFiles.containsKey(fileName))
                                .collect(Collectors.toList());

                List<String> correctSizeFiles = correctNameFiles.stream()
                                .filter(fileName -> (long) storageFiles.get(fileName) == (long) dbFiles.get(fileName))
                                .collect(Collectors.toList());

                List<String> wrongSizeFiles = correctNameFiles.stream()
                                .filter(fileName -> (long) storageFiles.get(fileName) != (long) dbFiles.get(fileName))
                                .collect(Collectors.toList());

                // why the second iteration without the new HashSet throws a call site
                // initialization exception?
                List<String> missingFiles = new HashSet<>(dbFiles.keySet()).stream()
                                .filter(fileName -> !storageFiles.containsKey(fileName))
                                .collect(Collectors.toList());

                for (String fileName : wrongSizeFiles) {
                        logger.info(
                                        "wrong size " + fileName + ", state: " + state + ", db: "
                                                        + dbFiles.get(fileName) + ", file: "
                                                        + storageFiles.get(fileName));
                }

                for (String fileName : missingFiles) {
                        if (deleteDatasetsOfMissingFiles != null && deleteDatasetsOfMissingFiles == true) {

                                logger.info("delete datasets of missing file " + fileName + ", state: " + state
                                                + ", db: "
                                                + dbFiles.get(fileName));
                                sessionDbAdminClient.deleteFile(UUID.fromString(fileName));
                        } else {

                                logger.info("missing file " + fileName + ", state: " + state + ", db: "
                                                + dbFiles.get(fileName));
                        }

                }

                long correctSizeTotal = correctSizeFiles.stream().mapToLong(dbFiles::get).sum();
                long wrongSizeTotal = wrongSizeFiles.stream().mapToLong(dbFiles::get).sum();
                long missingFilesTotal = missingFiles.stream().mapToLong(dbFiles::get).sum();

                logger.info("state " + state + ", " + correctSizeFiles.size() + " files ("
                                + FileUtils.byteCountToDisplaySize(correctSizeTotal)
                                + ") are fine");
                logger.info("state " + state + ", " + wrongSizeFiles.size() + " files ("
                                + FileUtils.byteCountToDisplaySize(wrongSizeTotal)
                                + ") have wrong size");
                logger.info("state " + state + ", " + missingFiles.size() + " files ("
                                + FileUtils.byteCountToDisplaySize(missingFilesTotal)
                                + ") are missing files or created during this check");
        }

        public static Set<File> deleteOldUploads(List<File> uploadingDbFiles, SessionDbAdminClient sessionDbAdminClient,
                        Long uploadMaxHours) throws RestException {

                Set<File> deletedFiles = new HashSet<>();

                logger.info("uploading files: " + uploadingDbFiles.size());

                // optionally delete old uploads
                if (uploadMaxHours != null) {
                        logger.info("delete uploads older than " + uploadMaxHours + " h");

                        for (File file : uploadingDbFiles) {

                                long hours = -1;

                                if (file.getFileCreated() != null) {
                                        hours = Duration.between(file.getFileCreated(), Instant.now()).toHours();
                                }

                                // delete when equal so that all uploads can be deleted by setting
                                // uploadMaxHours to 0
                                if (hours == -1 || hours >= uploadMaxHours) {

                                        logger.info("delete upload " + file.getFileId() + " "
                                                        + FileUtils.byteCountToDisplaySize(file.getSize()) + ", age: "
                                                        + hours
                                                        + " h exceeds max age " + uploadMaxHours + " h");
                                        logger.info(RestUtils.asJson(file));

                                        sessionDbAdminClient.deleteFile(file.getFileId());

                                        deletedFiles.add(file);
                                }
                        }

                } else {
                        logger.info("deletion of old uploads was not requested");
                }

                return deletedFiles;
        }
}