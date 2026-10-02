package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.SessionToken.Access;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

/**
 * Create dataset and session tokens
 * 
 * Check that user is allowed to access the requested resources and then get
 * the token from the auth service.
 * 
 * @author klemela
 *
 */
@Path("tokens")
public class SessionDbTokenResource {

    @SuppressWarnings("unused")
    private static Logger logger = LogManager.getLogger();

    /*
     * The longest validity that clients can ask for and the default when the
     * client doesn't ask for anything
     *
     * These tokens can't be revoked, so they must not be valid for long. The
     * defaults are enough for the web app, which doesn't set the validity at all.
     * The defaults are set here and not left for auth, so that the max holds even
     * if the defaults of auth change. Scheduler creates longer session tokens for
     * jobs.
     */
    static final Duration SESSION_TOKEN_MAX_VALID = Duration.ofHours(24);
    static final Duration DATASET_TOKEN_MAX_VALID = Duration.ofMinutes(10);
    static final Duration SESSION_TOKEN_DEFAULT_VALID = Duration.ofHours(24);
    static final Duration DATASET_TOKEN_DEFAULT_VALID = Duration.ofSeconds(60);

    public static final String QP_READ_WRITE = "readWrite";

    private RuleTable ruleTable;

    private AuthenticationClient authService;

    public SessionDbTokenResource(RuleTable ruleTable, AuthenticationClient authService) {
        this.ruleTable = ruleTable;
        this.authService = authService;
    }

    @POST
    @RolesAllowed({ Role.CLIENT, Role.SCHEDULER })
    @Path("sessions/{sessionId}")
    @Produces(MediaType.TEXT_PLAIN)
    @Transaction
    public Response postSessionToken(@PathParam("sessionId") UUID sessionId, @QueryParam("valid") String validString,
            @QueryParam(QP_READ_WRITE) String readWriteString, @Context SecurityContext sc) throws IOException {

        AuthPrincipal authPrincipal = (AuthPrincipal) sc.getUserPrincipal();

        boolean readWrite = false;

        if (readWriteString != null) {
            readWrite = Boolean.parseBoolean(readWriteString);
        }

        // client can create read-only tokens for session-worker
        String username = sc.getUserPrincipal().getName();
        Access access;

        if (readWrite) {
            access = Access.READ_WRITE;
        } else {
            access = Access.READ_ONLY;
        }

        // scheduler can create read-write tokens for jobs
        if (authPrincipal.getRoles().contains(Role.SCHEDULER)) {
            username = Role.SINGLE_SHOT_COMP;
            access = Access.READ_WRITE;
        }

        boolean requireReadWrite = access == Access.READ_WRITE;

        // check that the user is allowed to access the session (with auth token)
        Session session = ruleTable.checkSessionAuthorization(sc, sessionId, requireReadWrite, false);

        Instant valid = AuthTokens.parseValid(validString);

        if (!authPrincipal.getRoles().contains(Role.SCHEDULER)) {
            valid = defaultOrCheckMaxValid(valid, SESSION_TOKEN_DEFAULT_VALID, SESSION_TOKEN_MAX_VALID);
        }

        if (session.getSessionId() == null) {
            throw new IllegalArgumentException("cannot create token for null session");
        }

        String sessionDbToken;
        try {
            sessionDbToken = authService.createSessionToken(username, sessionId, valid, access);

        } catch (RestException e) {
            throw new InternalServerErrorException("failed to get restricted token from auth", e);
        }

        return Response.ok(sessionDbToken).build();
    }

    @POST
    @RolesAllowed(Role.CLIENT)
    @Path("sessions/{sessionId}/datasets/{datasetId}")
    @Produces(MediaType.TEXT_PLAIN)
    @Transaction
    public Response postDatasetToken(@PathParam("sessionId") UUID sessionId, @PathParam("datasetId") UUID datasetId,
            @QueryParam("valid") String validString, @Context SecurityContext sc) throws IOException {

        // check that the user is allowed to access the session (with auth token)
        // read-only access is enough, because this doesn't change the session (and is
        // needed for example sessions)
        Dataset dataset = ruleTable.checkDatasetReadAuthorization(sc, sessionId, datasetId);

        Session session = ruleTable.getSession(dataset.getSessionId());

        Instant valid = AuthTokens.parseValid(validString);

        valid = defaultOrCheckMaxValid(valid, DATASET_TOKEN_DEFAULT_VALID, DATASET_TOKEN_MAX_VALID);

        String username = sc.getUserPrincipal().getName();

        if (session.getSessionId() == null) {
            throw new IllegalArgumentException("cannot create token for null session");
        }

        if (dataset.getDatasetId() == null) {
            throw new IllegalArgumentException("cannot create token for null dataset");
        }

        String datasetToken = null;
        try {
            datasetToken = authService.createDatasetToken(username, sessionId, datasetId, valid);

        } catch (RestException e) {
            throw new InternalServerErrorException("failed to get restricted token from auth");
        }

        return Response.ok(datasetToken).build();
    }

    /**
     * Apply the default validity or refuse validity that is longer than the max
     *
     * The client computes the expiration from its own clock before the request, so
     * allow a bit of slack for the clock difference and the transit time. Without
     * it a request for exactly the max validity would fail intermittently.
     *
     * @param valid        requested validity or null for the default
     * @param defaultValid
     * @param max
     * @return the validity to use, never null
     */
    private static Instant defaultOrCheckMaxValid(Instant valid, Duration defaultValid, Duration max) {
        if (valid == null) {
            return Instant.now().plus(defaultValid);
        }
        if (valid.isAfter(Instant.now().plus(max).plus(Duration.ofMinutes(1)))) {
            String maxString = max.toMinutes() < 60 ? max.toMinutes() + " minutes" : max.toHours() + " hours";
            throw new BadRequestException("token can't be valid for longer than " + maxString);
        }
        return valid;
    }
}
