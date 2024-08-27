package fi.csc.chipster.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * HTTP download test
 * 
 * For testing different TLS, cipher and HTTP versions to troubleshoot
 * transfer errors.
 */
public class HttpDownload {

    public static Logger logger = LogManager.getLogger();

    /**
     * @param args
     * @throws Exception
     */
    public static void main(String args[]) throws Exception {

        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: HttpDownload URL FILE [ -v | --tls1.2 | --http2 | --chacha20 | --aes256 ]");
            System.exit(1);
        }

        URL url = new URI(args[0]).toURL();
        File file = new File(args[1]);

        boolean verbose = false;
        boolean tls12 = false;
        boolean http2 = false;
        boolean chacha20 = false;
        boolean aes256 = false;

        if (args.length == 3) {
            String option = args[2];

            if ("-v".equals(option)) {
                verbose = true;

            } else if ("--tls1.2".equals(option)) {

                tls12 = true;

            } else if ("--http2".equals(option)) {

                http2 = true;

            } else if ("--chacha20".equals(option)) {

                chacha20 = true;

            } else if ("--aes256".equals(option)) {

                aes256 = true;
            } else {
                logger.error("unkonwn option: " + option);
                System.exit(1);
            }
        }

        HttpClientTransport transport = null;

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();

        if (tls12) {
            sslContextFactory.setIncludeProtocols("TLSv1.2");
        }

        if (chacha20) {
            // long names in TLSv1.2: TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
            // short names in TLSv1.3: TLS_AES_256_GCM_SHA384
            if (tls12) {
                // the current argument parsing doesn't support multiple options
                // change the defaults or improve parsing to use this
                sslContextFactory.setIncludeCipherSuites(new String[] {
                        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                });
            } else {
                sslContextFactory.setIncludeCipherSuites(new String[] {
                        "TLS_CHACHA20_POLY1305_SHA256",
                });
            }
        }

        if (aes256) {

            if (tls12) {
                sslContextFactory.setIncludeCipherSuites(new String[] {
                        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"
                });
            } else {
                sslContextFactory.setIncludeCipherSuites(new String[] {
                        "TLS_AES_256_GCM_SHA384",
                });
            }
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

        HttpClient httpClient = new HttpClient(transport);

        // Add the SslHandshakeListener as bean to HttpClient.
        // The listener will be notified of TLS handshakes success and failure.
        httpClient.addBean(getSslHandshakeListener(verbose));

        httpClient.start();

        // Perform a simple GET and wait for the response.

        InputStreamResponseListener listener = new InputStreamResponseListener();

        httpClient.newRequest(url.toString()).method("GET").send(listener);

        // Wait for the response headers to arrive.
        Response response = listener.get(15, TimeUnit.SECONDS);

        logger.info("http version: " + response.getVersion());

        // Look at the response before streaming the content.
        if (response.getStatus() == HttpStatus.OK_200) {
            // Use try-with-resources to close input stream.
            try (InputStream responseContent = listener.getInputStream()) {
                FileUtils.copyInputStreamToFile(responseContent, file);
            }
        } else {
            response.abort(new IOException(
                    "Unexpected HTTP response: " + response.getStatus() + " " + response.getReason()));
        }

        httpClient.stop();

        // OpendJDK supports http2, but does not allow it to be forced like "curl
        // --http2-prior-knowledge"

        // HttpClient httpClient = HttpClient.newHttpClient();

        // HttpRequest req = HttpRequest.newBuilder().uri(url.toURI()).GET()
        // .version(Version.HTTP_2)
        // .build();

        // HttpResponse<Path> resp = httpClient.send(req,
        // HttpResponse.BodyHandlers.ofFile(file.toPath()));

        // System.out.println("Response code: " + resp.statusCode());
    }

    private static SslHandshakeListener getSslHandshakeListener(boolean verbose) {
        return new SslHandshakeListener() {
            @Override
            public void handshakeSucceeded(Event event) throws SSLException {

                SSLEngine sslEngine = event.getSSLEngine();

                if (verbose) {

                    for (String protocol : sslEngine.getEnabledProtocols()) {
                        logger.info("enabled protocol: " + protocol);
                    }

                    for (String cipher : sslEngine.getEnabledCipherSuites()) {
                        logger.info("enabled cipher: " + cipher);
                    }
                }

                logger.info("protocol: " + sslEngine.getSession().getProtocol());
                logger.info("cipher: " + sslEngine.getSession().getCipherSuite());
                // logger.info("peerHost: " + sslEngine.getPeerHost());
                // logger.info("peerPort: " + sslEngine.getPeerPort());
                // logger.info("applicationProtocol: " + sslEngine.getApplicationProtocol());
                // logger.info("handshakeApplicationProtocol: " +
                // sslEngine.getHandshakeApplicationProtocol());
                // logger.info("handshakeStatus: " + sslEngine.getHandshakeStatus());
            }
        };
    }
}
