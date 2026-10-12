package fi.csc.chipster.sessiondb.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;

public class SessionDbAdminResourceUnitTest {

    private static File file(long size) {
        File file = new File();
        file.setFileId(UUID.randomUUID());
        file.setSize(size);
        return file;
    }

    private static Dataset dataset(File file) {
        Dataset dataset = new Dataset();
        dataset.setFile(file);
        return dataset;
    }

    @Test
    public void getSessionSize() {
        assertEquals(0, SessionDbAdminResource.getSessionSize(List.of()));

        // datasets without a file don't count
        assertEquals(3, SessionDbAdminResource.getSessionSize(
                List.of(dataset(file(1)), dataset(file(2)), dataset(null))));
    }

    @Test
    public void getSessionSizeOfSharedFile() {
        File shared = file(5);

        // a File shared by several datasets counts once
        assertEquals(12, SessionDbAdminResource.getSessionSize(
                List.of(dataset(shared), dataset(shared), dataset(file(7)))));
    }
}
