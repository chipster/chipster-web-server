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
import fi.csc.chipster.s3storage.checksum.ChecksumException;
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
 * This is implemented as a servlet to show error in the browser when
 * an exception during the download. I wasn't able to create the error in Jersey
 * and others have had the same problem too:
 * https://github.com/eclipse-ee4j/jersey/issues/3850.
 * 
 * It is important to show this error for the user, because otherwise the user
 * might
 * think that he/she has a complete copy of the file. The browser does the
 * downloading, so we don't have any
 * way in the client side to monitor its progress.
 * 
 * Simply throwing an IOException from the ServletOutputStream seems to be
 * enough
 * in servlet. However, there is a bit more work with parsing the path.
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

    public FileBrokerResourceServlet(FileBrokerApi fileBrokerApi) {
        this.fileBrokerApi = fileBrokerApi;
    }

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

        if (range == null || range.isEmpty()) {
            /*
             * Set content-length header
             * 
             * This is not strictly needed, but it's difficult to report errors after the
             * response code has been sent, but at
             * least Firefox seems to show the download as blocked or failed if the stream
             * doesn't have as many bytes as we say here.
             */
            // response.header("content-length", dataset.getFile().getSize());
        }

        // configure filename for response
        if (!download) {
            RestUtils.configureFilename(response, dataset.getName());
        } else {
            RestUtils.configureForDownload(response, dataset.getName());
        }

        if (type) {
            // rendenring a html file in an iFrame requires the Content-Type header
            response.setContentType(this.fileBrokerApi.getType(dataset).toString());
        } else {
            /*
             * HTTP messages should contain content-type. but it's not required. The old
             * servlet implementation didn't set it and the browsers were guessing it fine.
             * I didn't find a way to remove the header in Jersey, but the wildcard
             * content-type seems to cause the same end result.
             */
            response.setContentType(MediaType.WILDCARD_TYPE.getType());
        }

        InputStream fileStream = this.fileBrokerApi.getDataset(dataset, range, userToken);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        OutputStream output = response.getOutputStream();

        try {

            IOUtils.copyLarge(fileStream, output);
        } catch (ChecksumException e) {
            throw new IOException("checksum error", e);
        }

    }

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

        this.fileBrokerApi.putDataset(idPair.getSessionId(), idPair.getDatasetId(), request.getInputStream(),
                chunkNumber, chunkSize,
                flowTotalChunks,
                flowTotalSize, temporary, userToken);

        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

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
