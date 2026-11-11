package fi.csc.chipster.filebroker;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.FinickyHttpClient.Verbosity;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class RestFileBrokerClient implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger();

    private WebTarget fileBrokerTarget;

    private CredentialsProvider credentials;

    private HttpClients clients;

    /**
     * Whether this instance created the clients and has to close them in close()
     */
    private boolean ownsClients;

    /**
     * The HTTP clients of a RestFileBrokerClient
     *
     * A Jersey client for the uploads and a FinickyHttpClient for the downloads.
     * Neither holds per-user state: the credentials are sent as a header in each
     * request, so one pair can serve the requests of all users. A service that
     * creates a RestFileBrokerClient for each request should create the pair once
     * with newHttpClients(), give it to each RestFileBrokerClient and close() it
     * at the end, instead of starting a new thread pool for each request.
     *
     * Only RestFileBrokerClient can create the pair, which guarantees that the
     * Jersey client has no credentials of its own: a client with fixed credentials
     * would send them whenever a request has none, as the wrong user.
     */
    public static class HttpClients implements AutoCloseable {

        private final Client jerseyClient;
        private final FinickyHttpClient downloadClient;

        private HttpClients(Client jerseyClient, FinickyHttpClient downloadClient) {
            this.jerseyClient = jerseyClient;
            this.downloadClient = downloadClient;
        }

        /**
         * Stop the clients
         *
         * The Jetty HttpClient of the downloads has its own thread pool, which
         * keeps running until the client is stopped.
         */
        @Override
        public void close() {
            try {
                this.downloadClient.stop();
            } catch (Exception e) {
                logger.warn("failed to stop the file-broker download client", e);
            }
            this.jerseyClient.close();
        }
    }

    /**
     * Create a client with its own HTTP clients
     *
     * Starts a Jetty HttpClient for the downloads, with its own thread pool, and
     * creates a Jersey client for the uploads. Both live until close() is called,
     * so this suits a client that lives as long as the process. A service that
     * needs a client for each request should share the HTTP clients between the
     * requests instead, see the other constructor.
     *
     * @param serviceLocator
     * @param credentials    sent in each request as basic authentication, or null
     *                       to send none (for testing). Nothing is sent either
     *                       when the username or the password is null
     * @param role           set to Role.CLIENT to use the public file-broker
     *                       address, anything else e.g. Role.SERVER to the internal
     *                       address
     */
    public RestFileBrokerClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials, String role) {

        this(serviceLocator, credentials, role, null, false, null);
    }

    public RestFileBrokerClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials,
            String role, String downloadTlsVersion, boolean downloadHttp2, String downloadCipher) {

        // resolve the address first: it asks the service-locator, and if that fails,
        // nobody could stop the clients that were started already
        this(getFileBrokerUri(serviceLocator, role), credentials,
                newHttpClients(downloadTlsVersion, downloadHttp2, downloadCipher), true);
    }

    /**
     * Create a client that uses shared HTTP clients
     *
     * See {@link HttpClients}. The caller owns the clients, close() doesn't stop
     * them.
     *
     * @param serviceLocator
     * @param credentials    see the other constructor
     * @param role           see the other constructor
     * @param clients        shared clients from newHttpClients()
     */
    public RestFileBrokerClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials, String role,
            HttpClients clients) {

        this(getFileBrokerUri(serviceLocator, role), credentials, clients, false);
    }

    private RestFileBrokerClient(String fileBrokerUri, CredentialsProvider credentials, HttpClients clients,
            boolean ownsClients) {

        this.credentials = credentials;
        this.clients = clients;
        this.ownsClients = ownsClients;

        // the credentials are added to each request, so the client itself can be
        // shared between users
        this.fileBrokerTarget = clients.jerseyClient.target(fileBrokerUri);
    }

    /**
     * Create the HTTP clients for sharing between RestFileBrokerClients, see
     * {@link HttpClients}
     */
    public static HttpClients newHttpClients() {
        return newHttpClients(null, false, null);
    }

    private static HttpClients newHttpClients(String downloadTlsVersion, boolean downloadHttp2,
            String downloadCipher) {

        // the Jetty client is the one that can fail to start, so create it first:
        // then a failure leaves no Jersey client behind
        FinickyHttpClient downloadClient = new FinickyHttpClient(downloadTlsVersion, downloadHttp2, downloadCipher,
                Verbosity.CHOSEN_VERSIONS);

        return new HttpClients(newJerseyClient(), downloadClient);
    }

    /**
     * Create the Jersey client for the uploads
     *
     * Its authentication feature has no credentials of its own: they are given
     * in the properties of each request, so the client can be shared between
     * users.
     */
    private static Client newJerseyClient() {
        return AuthenticationClient.getClient().register(HttpAuthenticationFeature.basicBuilder().build());
    }

    private static String getFileBrokerUri(ServiceLocatorClient serviceLocator, String role) {
        if (Role.CLIENT.equals(role)) {
            return serviceLocator.getPublicUri(Role.FILE_BROKER);
        } else {
            // get with credentials in ServiceLocator
            return serviceLocator.getInternalUri(Role.FILE_BROKER);
        }
    }

    /**
     * Close the HTTP clients, if this instance created them
     *
     * Does nothing when the clients were given to the constructor: their owner
     * closes them.
     */
    @Override
    public void close() {
        if (ownsClients) {
            this.clients.close();
        }
    }

    /**
     * Username and password of one request
     */
    private record BasicCredentials(String username, String password) {
    }

    /**
     * The credentials to send in a request, or null to send none
     *
     * The same rule for the uploads and the downloads: nothing is sent when there
     * are no credentials or either part of them is null. Read once per request:
     * the provider may check or refresh its token on each read, and a refresh
     * between two reads could pair a username with the password of another token.
     */
    private BasicCredentials readCredentials() {
        if (credentials == null) {
            return null;
        }
        String username = credentials.getUsername();
        String password = credentials.getPassword();
        if (username == null || password == null) {
            return null;
        }
        return new BasicCredentials(username, password);
    }

    // targets

    private WebTarget getDatasetTarget(UUID sessionId, UUID datasetId) {
        return fileBrokerTarget.path("sessions").path(sessionId.toString()).path("datasets").path(datasetId.toString());
    }

    // methods

    public void upload(UUID sessionId, UUID datasetId, File file) throws RestException, IOException {

        try (FileInputStream in = new FileInputStream(file)) {
            upload(sessionId, datasetId, in, file.length());
        }
    }

    public void upload(UUID sessionId, UUID datasetId, InputStream inputStream, Long size) throws RestException {
        upload(sessionId, datasetId, inputStream, size, false);
    }

    public void upload(UUID sessionId, UUID datasetId, InputStream inputStream, Long size, boolean temporary)
            throws RestException {
        WebTarget target = getDatasetTarget(sessionId, datasetId);

        // Use chunked encoding to disable buffering. HttpUrlConnector in
        // Jersey buffers the whole file before sending it by default, which
        // won't work with big files.
        target.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");

        if (temporary) {
            target = target.queryParam(FileBrokerResourceServlet.QP_TEMPORARY, Boolean.TRUE.toString());
        }

        if (size != null) {
            target = target.queryParam(FileBrokerResourceServlet.QP_FLOW_TOTAL_SIZE, size);
        }

        Invocation.Builder request = target.request();

        BasicCredentials basicCredentials = readCredentials();
        if (basicCredentials != null) {
            // the feature of newJerseyClient() sends these as a preemptive basic
            // authentication header
            request = request
                    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME,
                            basicCredentials.username())
                    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD,
                            basicCredentials.password());
        }

        Response response = request.put(Entity.entity(inputStream, MediaType.APPLICATION_OCTET_STREAM),
                Response.class);
        if (!RestUtils.isSuccessful(response.getStatus())) {
            throw new RestException("upload failed ", response, target.getUri());
        }
    }

    public void download(UUID sessionId, UUID datasetId, File destFile) throws RestException, IOException {

        try (InputStream inStream = download(sessionId, datasetId)) {
            Files.copy(inStream, destFile.toPath());
        }
    }

    public InputStream download(UUID sessionId, UUID datasetId) throws RestException {
        return this.download(sessionId, datasetId, (Long) null, null);
    }

    public InputStream download(UUID sessionId, UUID datasetId, Long maxBytes, Long fileSize) throws RestException {
        WebTarget target = getDatasetTarget(sessionId, datasetId);

        String username = null;
        String password = null;
        BasicCredentials basicCredentials = readCredentials();
        if (basicCredentials != null) {
            username = basicCredentials.username();
            password = basicCredentials.password();
        }

        // FinickyHttpClient configured to use specific protocol versions
        return this.clients.downloadClient.downloadInputStream(target.getUri(), maxBytes, fileSize, username,
                password);

        // simple download with the default client:

        // Response response = target.request().get(Response.class);
        // if (!RestUtils.isSuccessful(response.getStatus())) {
        // throw new RestException("getting input stream failed", response,
        // target.getUri());
        // }
        // return response.readEntity(InputStream.class);
    }
}
