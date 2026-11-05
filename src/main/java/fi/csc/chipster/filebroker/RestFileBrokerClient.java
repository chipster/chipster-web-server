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

    private Client jerseyClient;

    private FinickyHttpClient finickyHttpClient;

    /**
     * Whether this instance created the clients and has to stop them in close()
     */
    private boolean ownsClients;

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
        this(getFileBrokerUri(serviceLocator, role), credentials, newJerseyClient(),
                new FinickyHttpClient(downloadTlsVersion, downloadHttp2, downloadCipher, Verbosity.CHOSEN_VERSIONS),
                true);
    }

    /**
     * Create a client that uses shared HTTP clients
     *
     * The HTTP clients hold no per-user state: the credentials are sent as a
     * header in each request. So one Jersey client and one FinickyHttpClient can
     * serve the requests of all users, and a service that creates a
     * RestFileBrokerClient for each request should create them once and share
     * them, instead of starting a new thread pool for each request. The caller
     * owns the clients, close() doesn't stop them.
     *
     * @param serviceLocator
     * @param credentials       see the other constructor
     * @param role              see the other constructor
     * @param jerseyClient      shared client for the uploads, from
     *                          newJerseyClient()
     * @param finickyHttpClient shared client for the downloads
     */
    public RestFileBrokerClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials, String role,
            Client jerseyClient, FinickyHttpClient finickyHttpClient) {

        this(getFileBrokerUri(serviceLocator, role), credentials, jerseyClient, finickyHttpClient, false);
    }

    private RestFileBrokerClient(String fileBrokerUri, CredentialsProvider credentials, Client jerseyClient,
            FinickyHttpClient finickyHttpClient, boolean ownsClients) {

        if (!jerseyClient.getConfiguration().isRegistered(HttpAuthenticationFeature.class)) {
            throw new IllegalArgumentException("the Jersey client must be created with newJerseyClient()");
        }

        this.credentials = credentials;
        this.jerseyClient = jerseyClient;
        this.finickyHttpClient = finickyHttpClient;
        this.ownsClients = ownsClients;

        // the credentials are added to each request, so the client itself can be
        // shared between users
        this.fileBrokerTarget = jerseyClient.target(fileBrokerUri);
    }

    /**
     * Create a Jersey client for the uploads
     *
     * Its authentication feature has no credentials of its own: they are given
     * in the properties of each request, so the client can be shared between
     * users. Creating a Jersey client is heavy, so a service that creates a
     * RestFileBrokerClient for each request should create this once and give it
     * to the constructor.
     */
    public static Client newJerseyClient() {
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
     * Stop the HTTP clients, if this instance created them
     *
     * The Jetty HttpClient of the downloads has its own thread pool, which keeps
     * running until the client is stopped. Does nothing when the clients were
     * given to the constructor: their owner stops them.
     */
    @Override
    public void close() {
        if (!ownsClients) {
            return;
        }
        try {
            this.finickyHttpClient.stop();
        } catch (Exception e) {
            logger.warn("failed to stop the file-broker download client", e);
        }
        this.jerseyClient.close();
    }

    /**
     * Whether to send the credentials
     *
     * The same rule for the uploads and the downloads: nothing is sent when there
     * are no credentials or either part of them is null.
     */
    private boolean hasCredentials() {
        return credentials != null && credentials.getUsername() != null && credentials.getPassword() != null;
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

        if (hasCredentials()) {
            // the feature of newJerseyClient() sends these as a preemptive basic
            // authentication header
            request = request
                    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME,
                            credentials.getUsername())
                    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD,
                            credentials.getPassword());
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
        if (hasCredentials()) {
            username = credentials.getUsername();
            password = credentials.getPassword();
        }

        // FinickyHttpClient configured to use specific protocol versions
        return this.finickyHttpClient.downloadInputStream(target.getUri(), maxBytes, fileSize, username, password);

        // simple download with the default client:

        // Response response = target.request().get(Response.class);
        // if (!RestUtils.isSuccessful(response.getStatus())) {
        // throw new RestException("getting input stream failed", response,
        // target.getUri());
        // }
        // return response.readEntity(InputStream.class);
    }
}
