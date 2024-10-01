package fi.csc.chipster.web;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;

/**
 * Serve index.html as 404 error page
 * 
 * A single page application can invent its own URLs. For those to work on the
 * page load, the server must respond with the application (starting from the
 * index.html) when ever any of those URLs is used. Then the application can
 * handle the URL itself. In practice we can't know what URLs the application is
 * going to handle but can simply replace all 404 responses.
 * 
 * @author klemela
 */
public class PushStateErrorHandler extends ErrorHandler {

    private Path errorPagePath;

    public PushStateErrorHandler(Path path) {

        this.errorPagePath = path;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback)
            throws Exception {

        String errorPageContents = Files.readString(errorPagePath);

        if ("GET".equals(request.getMethod()) && response.getStatus() == 404) {

            // keep 404 status code to make debugging easier
            // response.setStatus(200);
            response.write(true, ByteBuffer.wrap(errorPageContents.getBytes()), callback);

            callback.succeeded();
            return true;
        } else {
            return super.handle(request, response, callback);
        }
    }
}
