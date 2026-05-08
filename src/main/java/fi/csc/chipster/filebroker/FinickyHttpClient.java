package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.client.Authentication;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import fi.csc.chipster.s3storage.checksum.CheckedStream;
import fi.csc.chipster.sessiondb.RestException;

/**
 * FinickyHttpClient
 * 
 * Http client which demands specific TLS, HTTP and cipher versions to
 * workaround problems in default versions.
 * 
 */
public class FinickyHttpClient {

    public static enum Verbosity {
        NORMAL,
        CHOSEN_VERSIONS,
        ENABLED_VERSIONS
    }

    public static Logger logger = LogManager.getLogger();

    private HttpClient jettyHttpClient;

    private Verbosity verbosity;

    private String username;

    private String password;

    public FinickyHttpClient(String username, String password, String tlsVersion, boolean http2, String cipher,
            Verbosity verbosity) {

        this.username = username;
        this.password = password;
        this.verbosity = verbosity;

        HttpClientTransport transport = null;

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();

        if (tlsVersion != null) {
            sslContextFactory.setIncludeProtocols("TLSv1.2");
        }

        if (cipher != null) {
            sslContextFactory.setIncludeCipherSuites(new String[] { cipher });
        }

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactory);

        if (http2) {

            HTTP2Client http2Client = new HTTP2Client(clientConnector);

            transport = new HttpClientTransportOverHTTP2(http2Client);

            // transport.setUseALPN(false);

        } else {
            transport = new HttpClientTransportDynamic(clientConnector);
        }

        jettyHttpClient = new HttpClient(transport);

        // Add the SslHandshakeListener as bean to HttpClient.
        // The listener will be notified of TLS handshakes success and failure.
        jettyHttpClient.addBean(getSslHandshakeListener(verbosity));

        try {
            jettyHttpClient.start();
        } catch (Exception e) {
            throw new RuntimeException("failed to start Jetty HTTP client", e);
        }

    }

    public InputStream downloadInputStream(URI uri) throws RestException {
        return this.downloadInputStream(uri, null, null);
    }

    /**
     * Return data from uri as InputStream
     * 
     * Beginning of file can be requested with maxBytes and fileSize. Valid range is
     * calculated based on these two values.
     * 
     * @param uri
     * @param maxBytes Maximum number of bytes to request or null. When maxBytes is
     *                 given, also fileSize must be provided. You should
     *                 provide a non-zero maximum request size here and let this
     *                 method handle the special case of small or empty files.
     * @param fileSize Size of the file or null.
     * @return
     * @throws RestException
     */
    public InputStream downloadInputStream(URI uri, Long maxBytes, Long fileSize) throws RestException {

        // Perform a simple GET and wait for the response.

        InputStreamResponseListener listener = new InputStreamResponseListener();

        Request request = jettyHttpClient.newRequest(uri.toString()).method(HttpMethod.GET);

        if (maxBytes != null) {
            if (fileSize == null) {
                /*
                 * fileSize is needed calculate a valid range request, when the file is smaller
                 * than maxBytes.
                 */
                throw new IllegalArgumentException("maxBytes requires fileSize");
            }

            if (maxBytes == 0 && fileSize != 0) {
                // there is no valid range request for empty files
                throw new IllegalArgumentException("cannot get zero bytes from non-empty file");
            }

            // request the whole file, if it's empty
            if (fileSize > 0) {
                long maxRequestBytes = Math.min(maxBytes, fileSize);

                request.headers(headers -> headers.add(HttpHeader.RANGE, "bytes=0-" + (maxRequestBytes - 1)));
            }
        }

        if (this.username != null && this.password != null) {

            // send Authorization header directly without waiting servers 401 response
            Authentication.Result authn = new BasicAuthentication.BasicResult(uri, username, password);
            authn.apply(request);
        }

        request.send(listener);

        try {
            // Wait for the response headers to arrive.
            Response response = listener.get(15, TimeUnit.SECONDS);

            if (verbosity == Verbosity.ENABLED_VERSIONS || verbosity == Verbosity.CHOSEN_VERSIONS) {
                logger.info("http version: " + response.getVersion());
            }

            // Look at the response before streaming the content.
            // Server should respond with 206 to range requests, but doesn't at the moment.
            // Lets allow it anyway to be compliant.
            if (response.getStatus() == HttpStatus.OK_200 || response.getStatus() == HttpStatus.PARTIAL_CONTENT_206) {

                InputStream remoteStream = listener.getInputStream();

                // if server sends a content-length, check that the stream length matches
                // otherwise rely on server closing the connection without sending the last
                // empty chunk
                String contentLengthString = response.getHeaders().get("Content-Length");

                if (contentLengthString != null) {
                    long contentLength = Long.parseLong(contentLengthString);
                    if (verbosity == Verbosity.ENABLED_VERSIONS || verbosity == Verbosity.CHOSEN_VERSIONS) {
                        logger.info("Content-Length: " + contentLength);
                    }
                    remoteStream = new CheckedStream(remoteStream, null, null, contentLength);
                }
                return remoteStream;

            } else {

                RestException exception = new RestException("getting input stream failed: " + response.getReason(),
                        response, uri);

                response.abort(exception);

                throw exception;

            }
        } catch (InterruptedException | TimeoutException | ExecutionException | IOException e) {
            throw new RuntimeException("getting input stream failed", e);
        }
    }

    private static SslHandshakeListener getSslHandshakeListener(Verbosity verbosity) {
        return new SslHandshakeListener() {
            @Override
            public void handshakeSucceeded(Event event) throws SSLException {

                SSLEngine sslEngine = event.getSSLEngine();

                if (verbosity == Verbosity.ENABLED_VERSIONS) {

                    for (String protocol : sslEngine.getEnabledProtocols()) {
                        logger.info("enabled protocol: " + protocol);
                    }

                    for (String cipher : sslEngine.getEnabledCipherSuites()) {
                        logger.info("enabled cipher: " + cipher);
                    }
                }

                if (verbosity == Verbosity.ENABLED_VERSIONS || verbosity == Verbosity.CHOSEN_VERSIONS) {
                    logger.info("protocol: " + sslEngine.getSession().getProtocol());
                    logger.info("cipher: " + sslEngine.getSession().getCipherSuite());
                }
                // logger.info("peerHost: " + sslEngine.getPeerHost());
                // logger.info("peerPort: " + sslEngine.getPeerPort());
                // logger.info("applicationProtocol: " + sslEngine.getApplicationProtocol());
                // logger.info("handshakeApplicationProtocol: " +
                // sslEngine.getHandshakeApplicationProtocol());
                // logger.info("handshakeStatus: " + sslEngine.getHandshakeStatus());
            }
        };
    }

    public void stop() throws Exception {
        this.jettyHttpClient.stop();
    }
}
