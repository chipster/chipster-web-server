package fi.csc.chipster.util;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.filebroker.FinickyHttpClient;
import fi.csc.chipster.filebroker.FinickyHttpClient.Verbosity;

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

        String cipher = null;
        String tlsVersion = null;
        Verbosity verbosity = Verbosity.CHOSEN_VERSIONS;

        if (tls12) {
            tlsVersion = "TLSv1.2";
        }

        if (chacha20) {
            // long names in TLSv1.2: TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
            // short names in TLSv1.3: TLS_AES_256_GCM_SHA384
            if (tls12) {
                // the current argument parsing doesn't support multiple options
                // change the defaults or improve parsing to use this
                cipher = "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256";
            } else {
                cipher = "TLS_CHACHA20_POLY1305_SHA256";
            }
        }

        if (aes256) {

            if (tls12) {
                cipher = "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384";
            } else {
                cipher = "TLS_AES_256_GCM_SHA384";
            }
        }

        if (verbose) {
            verbosity = Verbosity.ENABLED_VERSIONS;
        }

        FinickyHttpClient finickyHttpClient = new FinickyHttpClient(null, null, tlsVersion, http2, cipher, verbosity);

        try (InputStream is = finickyHttpClient.dowloadInputStream(url.toURI())) {

            FileUtils.copyInputStreamToFile(is, file);

        } finally {

            finickyHttpClient.stop();
        }

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
}
