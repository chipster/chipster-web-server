package fi.csc.chipster.filebroker;

import java.io.InputStream;

import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.model.File;

/**
 * Common interface for storage clients
 * 
 * FileBrokerResourceServlet uses this to upload and download files, regardless
 * of whether the storage is a file-storage or s3-storage.
 */
public interface StorageClient {

    InputStream download(File file, String range);

    void checkIfAppendAllowed(File file, Long chunkNumber, Long chunkSize, Long flowTotalChunks, Long flowTotalSize);

    File upload(File file, InputStream fileStream, Long chunkNumber, Long chunkSize, Long flowTotalChunks,
            Long flowTotalSize) throws RestException;

    boolean deleteAfterUploadException();

    void delete(File file) throws RestException;
}
