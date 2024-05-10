package fi.csc.chipster.filebroker;

public interface StorageAdminClient {

    String getStatus();

    /**
     * Return the storageId of the storage
     * 
     * Incorrect configuration can make file-storage and file-broker to disagree
     * about the ID of the file-storage. This is dangerous, because then the orphan
     * deletion will delete all files. This method allows UI to notice this
     * situation and disable dangerous options.
     * 
     * @param storageId
     * 
     * @return
     */
    String getStorageId();

    String getFileStats();

    void startCheck(Long uploadMaxHours, Boolean deleteDatasetsOfMissingFiles, Boolean checksums);

    void deleteOldOrphans();

}
