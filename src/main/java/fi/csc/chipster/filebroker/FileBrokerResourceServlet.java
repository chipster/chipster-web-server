package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServletUtils;
import fi.csc.chipster.s3storage.checksum.FileLengthException;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.model.Dataset;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;

/**
 * Servlet for uploading and downloading files
 * 
 * This is implemented as a servlet, because that allowed us to report errors to
 * client, when we were still using chunked encoding (because ZipSessionServlet
 * needed it). Now when we don't use chunked encoding anymore (Or do we
 * in range requests?), it might be possible to implement this with some higher
 * level API, like Jersey. It would make it easier to parse the request path.
 * 
 * It is important to show errors for the user, because otherwise the user
 * might think that he/she has a complete copy of the file. The browser does the
 * downloading, so we don't have any way in the client side to monitor its
 * progress.
 * 
 * @author klemela
 *
 */
public class FileBrokerResourceServlet extends HttpServlet {

    public static final String QP_FLOW_TOTAL_CHUNKS = "flowTotalChunks";
    public static final String QP_FLOW_CHUNK_SIZE = "flowChunkSize";
    public static final String QP_FLOW_CHUNK_NUMBER = "flowChunkNumber";
    public static final String QP_FLOW_TOTAL_SIZE = "flowTotalSize";
    public static final String QP_TEMPORARY = "temporary";
    public static final String QP_DOWNLOAD = "download";
    public static final String QP_TYPE = "type";

    public static final String HEADER_RANGE = "Range";

    @SuppressWarnings("unused")
    private static Logger logger = LogManager.getLogger();

    private FileBrokerApi fileBrokerApi;
    private boolean useChunkedEncoding;

    public FileBrokerResourceServlet(FileBrokerApi fileBrokerApi, boolean useChunkedEncoding) {
        this.fileBrokerApi = fileBrokerApi;
        this.useChunkedEncoding = useChunkedEncoding;
    }

    /**
     * Download a file
     * 
     * Expects path in form of sessions/SESSION_ID/datasets/DATASET_ID.
     * 
     * Query parameters:
     * download: set a header to ask browser to download this file instead of
     * showing it
     * type: set content-type header to inform browser about the type of this file
     * 
     * Supports HTTP range requests (s3-storage only from start) to get only a
     * specific part of the file.
     * 
     * In case of errors, e.g. file cheksum doesn't match, small files respond with
     * HTTP error code. With large files, the client notices a problem only if we
     * send too few bytes. Luckily this is probably the most common error. If we
     * have already sent the correct amount of bytes when we notice a checksum
     * error, we can't inform the client anymore. Or we could if would use chunked
     * encoding, but that has its own problems, explained in ZipSerssionServlet.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {

        DatasetIdPair idPair = parsePath(request);

        // get query parameters
        // empty string when set, otherwise null (boolean without value would have been
        // false)
        boolean download = request.getParameter(QP_DOWNLOAD) != null;
        boolean type = request.getParameter(QP_TYPE) != null;
        String range = request.getHeader(HEADER_RANGE);

        String userToken = ServletUtils.getToken(request);

        Dataset dataset;

        // checks authorization
        try {
            dataset = this.fileBrokerApi.getDatasetObject(idPair.getSessionId(), idPair.getDatasetId(), userToken,
                    false);
        } catch (RestException e) {
            throw ServletUtils.extractRestException(e);
        }

        // configure filename for response
        if (!download) {
            RestUtils.configureFilename(response, dataset.getName());
        } else {
            RestUtils.configureForDownload(response, dataset.getName());

            // otherwise firefox opens pdf files when trying to download it
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }

        if (type) {
            // rendering a html file in an iFrame requires the Content-Type header
            // and Chrome needs it to open pdf file in new tab
            response.setContentType(this.fileBrokerApi.getType(dataset));
        } else {
            /*
             * HTTP messages should contain content-type. but it's not required. The old
             * servlet implementation didn't set it and the browsers were guessing it fine.
             */
        }

        InputStream fileStream = this.fileBrokerApi.getDataset(dataset, range, userToken);

        response.setStatus(HttpServletResponse.SC_OK);

        if (!useChunkedEncoding && range == null) {
            // if content-lenth is set, browsers notice interrupted downloads
            logger.info("set content-length: " + dataset.getFile().getSize());
            response.setContentLengthLong(dataset.getFile().getSize());
        } else {
            // Jetty sets this automatically
            // response.setHeader("Transfer-Encoding", "chunked");
        }

        OutputStream output = response.getOutputStream();

        /*
         * Can throw ChecksumException or FileLengthException. Error messages in
         * browsers' download view:
         * 
         * Chrome: "Check internet connection"
         * Safari: "cannot parse response"
         * Firefox: "failed"
         */
        IOUtils.copyLarge(fileStream, output);

        /*
         * Close output stream only if the copyLarge() was successful
         * 
         * If an exception (ChecksumException, FileLengthException) was thrown, this is
         * skipped on purpose. Otherwise the Jetty writes and empty chunk, marking the
         * end of the chunked transfer-encoding and browser doesn't know that something
         * went wrong. So don't use try-with-resources to close it!
         */
        output.close();

        this.fileBrokerApi.afterDownload(dataset);
    }

    /**
     * Upload a file
     * 
     * Expects path in form of sessions/SESSION_ID/datasets/DATASET_ID. Client must
     * create a new Dataset before making this request.
     * 
     * Implements query parameters for flow.js to pause and resume uploads. All
     * uploads must provide the query parameter flowTotalSize to tell the size if
     * the file at the start of the request.
     */
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {

        DatasetIdPair idPair = parsePath(request);

        String userToken = ServletUtils.getToken(request);

        // can be null
        Long chunkNumber = NumberUtils.createLong(request.getParameter(QP_FLOW_CHUNK_NUMBER));
        Long chunkSize = NumberUtils.createLong(request.getParameter(QP_FLOW_CHUNK_SIZE));
        Long flowTotalChunks = NumberUtils.createLong(request.getParameter(QP_FLOW_TOTAL_CHUNKS));
        Long flowTotalSize = NumberUtils.createLong(request.getParameter(QP_FLOW_TOTAL_SIZE));
        Boolean temporary = Boolean.valueOf(request.getParameter(QP_TEMPORARY));

        try {
            this.fileBrokerApi.putDataset(idPair.getSessionId(), idPair.getDatasetId(), request.getInputStream(),
                    chunkNumber, chunkSize,
                    flowTotalChunks,
                    flowTotalSize, temporary, userToken);

        } catch (FileLengthException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getOutputStream().write(e.getMessage().getBytes());
            return;
        }

        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    /**
     * Parse request paths in this servlet
     * 
     * Higher level frameworks like Jersey would do this for us, but in a servlet we
     * have to do it ourselves.
     * 
     * @param request
     * @return
     */
    private DatasetIdPair parsePath(HttpServletRequest request) {

        String pathInfo = request.getPathInfo();

        if (pathInfo == null) {
            throw new NotFoundException();
        }

        // parse path
        String[] path = pathInfo.split("/");

        if (path.length != 5) {
            throw new NotFoundException();
        }

        if (!"".equals(path[0])) {
            throw new BadRequestException("path doesn't start with slash");
        }

        if (!"sessions".equals(path[1])) {
            throw new NotFoundException();
        }

        UUID sessionId = UUID.fromString(path[2]);

        if (!"datasets".equals(path[3])) {
            throw new NotFoundException();
        }

        UUID datasetId = UUID.fromString(path[4]);

        return new DatasetIdPair(sessionId, datasetId);
    }
}
