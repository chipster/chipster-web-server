package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;

public class FileUtilsTest {

    private static File file(FileState state) {
        File file = new File();
        file.setFileId(UUID.randomUUID());
        file.setState(state);
        return file;
    }

    @Test
    public void hasFile() {
        Dataset dataset = new Dataset();
        assertFalse(FileUtils.hasFile(dataset));

        dataset.setFile(file(FileState.COMPLETE));
        assertTrue(FileUtils.hasFile(dataset));
    }

    @Test
    public void isUploadFinished() {
        assertTrue(FileUtils.isUploadFinished(file(FileState.COMPLETE)));
        assertFalse(FileUtils.isUploadFinished(file(FileState.UPLOADING)));
        // files created before the state column was added are complete
        assertTrue(FileUtils.isUploadFinished(file(null)));
    }
}
