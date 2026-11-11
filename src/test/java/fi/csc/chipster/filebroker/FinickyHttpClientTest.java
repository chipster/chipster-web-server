package fi.csc.chipster.filebroker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.filebroker.FinickyHttpClient.Verbosity;
import fi.csc.chipster.sessiondb.RestException;

/**
 * A FinickyHttpClient is shared by all the downloads of a session-worker, so a
 * download that fails, or that the caller abandons, must not keep holding a
 * connection of the shared pool. These tests run the client against a local
 * Jetty server and check the pool after each kind of failure.
 */
public class FinickyHttpClientTest {

    private static final String SMALL_BODY = "hello";

    /**
     * Larger than the socket buffers, so that the server can't finish writing it
     * before the client has read it
     */
    private static final int BIG_BODY_SIZE = 16 * 1024 * 1024;

    private static Server server;
    private static URI baseUri;
    private static FinickyHttpClient client;

    @BeforeAll
    public static void start() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new TestHandler());
        server.start();
        baseUri = URI.create("http://localhost:" + connector.getLocalPort());

        client = new FinickyHttpClient(null, false, null, Verbosity.NORMAL);
    }

    @AfterAll
    public static void stop() throws Exception {
        client.stop();
        server.stop();
    }

    @Test
    public void connectionLimit() {
        assertEquals(128, client.getJettyHttpClient().getMaxConnectionsPerDestination());
    }

    @Test
    public void successfulDownload() throws Exception {
        assertEquals(SMALL_BODY, downloadSmall());
        awaitNoActiveConnections();
    }

    /**
     * An error status must abort the exchange, not leave its unread response on the
     * connection
     */
    @Test
    public void errorStatusReleasesConnection() throws Exception {
        assertThrows(RestException.class, () -> client.downloadInputStream(baseUri.resolve("/notfound")));

        awaitNoActiveConnections();
        assertEquals(SMALL_BODY, downloadSmall());
    }

    /**
     * The zip thread of a failed export closes its download stream in the middle
     * of the content. That must abort the exchange and free the connection.
     */
    @Test
    public void closedStreamReleasesConnection() throws Exception {
        try (InputStream in = client.downloadInputStream(baseUri.resolve("/big"))) {
            assertTrue(in.read(new byte[1024]) > 0);
            assertEquals(1, activeConnections());
        }

        awaitNoActiveConnections();
        assertEquals(SMALL_BODY, downloadSmall());
    }

    /**
     * A request that gets no response is dropped when the wait for the headers
     * ends. The wait ends by a timeout or an interrupt, which share the same
     * handling. Interrupt is used here, because the timeout is 15 seconds.
     */
    @Test
    public void abandonedWaitReleasesConnection() throws Exception {
        Thread testThread = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            testThread.interrupt();
        });
        interrupter.start();

        try {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> client.downloadInputStream(baseUri.resolve("/stall")));
            assertTrue(e.getCause() instanceof InterruptedException, "cause: " + e.getCause());
        } finally {
            // clear the flag if the interrupt arrived after the download gave up
            Thread.interrupted();
            interrupter.join();
        }

        awaitNoActiveConnections();
        assertEquals(SMALL_BODY, downloadSmall());
    }

    private static String downloadSmall() throws RestException, IOException {
        try (InputStream in = client.downloadInputStream(baseUri.resolve("/small"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Connections of the client that are in use by an exchange, plus the requests
     * that wait for a connection
     */
    private static int activeConnections() {
        HttpClient jetty = client.getJettyHttpClient();
        int count = 0;
        for (Destination destination : jetty.getDestinations()) {
            HttpDestination httpDestination = (HttpDestination) destination;
            AbstractConnectionPool pool = (AbstractConnectionPool) httpDestination.getConnectionPool();
            count += pool.getActiveConnectionCount() + httpDestination.getQueuedRequestCount();
        }
        return count;
    }

    /**
     * An abort closes the connection asynchronously, so give it a moment
     */
    private static void awaitNoActiveConnections() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (activeConnections() > 0) {
            if (Instant.now().isAfter(deadline)) {
                assertEquals(0, activeConnections(), "connections still active");
            }
            Thread.sleep(50);
        }
    }

    private static class TestHandler extends Handler.Abstract {

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {

            switch (request.getHttpURI().getPath()) {

            case "/small":
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, Integer.toString(SMALL_BODY.length()));
                Content.Sink.write(response, true, SMALL_BODY, callback);
                return true;

            case "/big":
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, Integer.toString(BIG_BODY_SIZE));
                try {
                    // blocking writes: this stops at the socket buffers until the client reads
                    // or goes away
                    byte[] chunk = new byte[64 * 1024];
                    for (int written = 0; written < BIG_BODY_SIZE; written += chunk.length) {
                        boolean last = written + chunk.length >= BIG_BODY_SIZE;
                        Content.Sink.write(response, last, ByteBuffer.wrap(chunk));
                    }
                    callback.succeeded();
                } catch (IOException e) {
                    callback.failed(e);
                }
                return true;

            case "/notfound":
                response.setStatus(404);
                callback.succeeded();
                return true;

            case "/stall":
                // never respond: the callback is never completed
                return true;

            default:
                response.setStatus(500);
                callback.succeeded();
                return true;
            }
        }
    }
}
