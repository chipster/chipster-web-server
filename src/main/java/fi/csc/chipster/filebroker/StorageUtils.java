package fi.csc.chipster.filebroker;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.sessiondb.RestException;
import io.jsonwebtoken.io.IOException;

public class StorageUtils {

        private static Logger logger = LogManager.getLogger();

        public static List<String> check(Map<String, Long> storageFiles, Map<String, Long> oldOrphanFiles,
                        Map<String, Long> dbFiles) throws RestException, IOException {

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

                List<String> orphanFiles = new HashSet<>(storageFiles.keySet()).stream()
                                .filter(fileName -> !dbFiles.containsKey(fileName))
                                .collect(Collectors.toList());

                for (String fileName : wrongSizeFiles) {
                        logger.info(
                                        "wrong size " + fileName + ", db: " + dbFiles.get(fileName) + ", file: "
                                                        + storageFiles.get(fileName));
                }

                for (String fileName : missingFiles) {
                        logger.info("missing file " + fileName + ", db: " + dbFiles.get(fileName));
                }

                long correctSizeTotal = correctSizeFiles.stream().mapToLong(dbFiles::get).sum();
                long wrongSizeTotal = wrongSizeFiles.stream().mapToLong(dbFiles::get).sum();
                long missingFilesTotal = missingFiles.stream().mapToLong(dbFiles::get).sum();
                long orphanFilesTotal = orphanFiles.stream().mapToLong(storageFiles::get).sum();
                long oldOrphanFilesTotal = oldOrphanFiles.values().stream().mapToLong(s -> s).sum();

                logger.info(correctSizeFiles.size() + " files (" + FileUtils.byteCountToDisplaySize(correctSizeTotal)
                                + ") are fine");
                logger.info(wrongSizeFiles.size() + " files (" + FileUtils.byteCountToDisplaySize(wrongSizeTotal)
                                + ") have wrong size");
                logger.info(missingFiles.size() + " missing files or created during this check ("
                                + FileUtils.byteCountToDisplaySize(missingFilesTotal) + ")");
                logger.info(oldOrphanFiles.size() + " old orphan files ("
                                + FileUtils.byteCountToDisplaySize(oldOrphanFilesTotal) + ")");
                logger.info(orphanFiles.size() + " orphan files (" + FileUtils.byteCountToDisplaySize(orphanFilesTotal)
                                + ")");

                return orphanFiles;
        }
}