package fi.csc.chipster.filebroker;

import java.io.InputStream;

import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.model.File;

public interface StorageClient {

    InputStream download(File file, String range);

    void checkIfAppendAllowed(File file, Long chunkNumber, Long chunkSize, Long flowTotalChunks, Long flowTotalSize);

    File upload(File file, InputStream fileStream, Long chunkNumber, Long chunkSize, Long flowTotalChunks,
            Long flowTotalSize) throws RestException;

    boolean deleteAfterUploadException();

    void delete(File file) throws RestException;

}
