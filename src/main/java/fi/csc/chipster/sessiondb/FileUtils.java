package fi.csc.chipster.sessiondb;

import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;

public class FileUtils {
    public static boolean isEmpty(File file) {
        return file == null || (file.getFileId() == null && file.getChecksum() == null && file.getSize() == -1);
    }

    /**
     * Check if the dataset has a file
     * 
     * There is none when the upload never started, or when file-broker deleted the
     * file of a failed upload.
     * 
     * @param dataset
     * @return true if the dataset has a file in some storage
     */
    public static boolean hasFile(Dataset dataset) {
        return dataset.getFile() != null && dataset.getFile().getFileId() != null;
    }

    /**
     * Check if the upload of the file has finished
     * 
     * Before that file-storage would return only the chunks uploaded so far and S3
     * wouldn't have the object at all. Files created before the state column was
     * added (V13) have null state, but they are complete.
     * 
     * @param file
     * @return true if the file can be read
     */
    public static boolean isUploadFinished(File file) {
        FileState state = file.getState();
        return state == null || state == FileState.COMPLETE;
    }
}
