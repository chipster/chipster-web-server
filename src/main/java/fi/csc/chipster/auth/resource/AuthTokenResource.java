package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.SessionToken.Access;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.auth.model.UserToken;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.Transaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

/**
 * REST API resource for creating Chipster tokens
 * 
 * 
 * @author klemela
 *
 */
@Path("tokens")
public class AuthTokenResource {

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	public static final String QP_DATASET_ID = "datasetId";
	public static final String QP_ACCESS = "access";
	public static final String QP_VALID = "valid";
	public static final String QP_SESSION_ID = "sessionId";
	public static final String QP_USERNAME = "username";

	public static final String TOKEN_HEADER = "chipster-token";

	public static final String PATH_TOKENS = "tokens";
	public static final String PATH_PUBLIC_KEY = "publicKey";

	public static final String PATH_DATASET_TOKEN = "datasettoken";
	public static final String PATH_SESSION_TOKEN = "sessiontoken";

	private static final long DATASET_TOKEN_VALID_DEFAULT = 60; // seconds

	// session downloads may take several hours
	private static final long SESSION_TOKEN_VALID_DEFAULT = 60 * 60 * 24; // seconds

	private AuthTokens tokens;
	private UserTable userTable;

	public AuthTokenResource(AuthTokens tokenTable, UserTable userTable) throws URISyntaxException, IOException {
		this.tokens = tokenTable;
		this.userTable = userTable;
	}

	@POST
	@RolesAllowed(Role.PASSWORD)
	@Produces(MediaType.TEXT_PLAIN)
	@Transaction // getName() uses the db
	public Response createUserToken(@Context SecurityContext sc) {

		// curl -i -H "Content-Type: application/json" --user client:clientPassword -X
		// POST http://localhost:8081/auth/tokens

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
		String username = sc.getUserPrincipal().getName();

		if (username == null) {
			// RolesAllowed prevents this
			throw new NotAuthorizedException("username is null");
		}

		String token = tokens.createNewUserToken(username, principal.getRoles(),
				this.getName(username, principal.getRoles()));

		return Response.ok(token).build();
	}

	/**
	 * Check the token validity on the server
	 * 
	 * Most services shouldn't use this, but get the public key and check the token
	 * themselves
	 * for performance reasons.
	 * If that is impractical for some service, it can also use this endpoint to do
	 * the validation.
	 * 
	 * todo: find a way to pass the client's error (NotAuthorized or Forbidden), to
	 * make it
	 * easier to switch between these two ways of validation.
	 * 
	 * @param requestToken
	 * @param sc
	 * @return
	 */
	@GET
	@RolesAllowed(Role.SERVER)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction // getName() uses the db
	public Response checkUserToken(@HeaderParam(TOKEN_HEADER) String requestToken, @Context SecurityContext sc) {

		UserToken validToken;
		try {
			validToken = tokens.validateUserToken(requestToken);
		} catch (NotAuthorizedException | ForbiddenException e) {
			// NotAuthorized and Forbidden are not suitable here, because the server
			// authenticated correctly in the TokenRequestFilter
			throw new NotFoundException(e);
		}

		return Response.ok(validToken).build();
	}

	/**
	 * Get the public key
	 * 
	 * Other services can use the public key to validate tokens signed by the
	 * auth service.
	 * 
	 * @param sc
	 * @return
	 */
	@GET
	@Path(PATH_PUBLIC_KEY)
	// this could be also unauthenticated, but client hasn't needed it so far
	@RolesAllowed(Role.SERVER)
	public Response getPublicKey(@Context SecurityContext sc) {

		return Response.ok(this.tokens.getPublicKey()).build();
	}

	/**
	 * Exchange an expiring UserToken to one which is valid for longer
	 * 
	 * This allows tokens to expire faster when those are not in use.
	 * 
	 * @param sc
	 * @return
	 */
	@POST
	@Path("refresh")
	@RolesAllowed({ Role.CLIENT, Role.SERVER })
	@Produces(MediaType.TEXT_PLAIN)
	@Transaction // getName() uses the db
	public Response refreshUserToken(@Context SecurityContext sc) {

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
		String oldToken = principal.getTokenKey();

		String newToken = tokens.refreshUserToken(oldToken, this.getName(principal.getName(), principal.getRoles()));

		return Response.ok(newToken).build();
	}

	@GET
	@Path("check")
	@RolesAllowed({ Role.CLIENT, Role.SERVER })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction // getName() uses the db
	public Response checkClientToken(@Context SecurityContext sc) {

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
		String token = principal.getTokenKey();

		// throws if fails
		UserToken validToken = tokens.validateUserToken(token);

		return Response.ok(validToken).build();
	}

	/**
	 * Get user's human-readable name for user interface
	 * 
	 * @param username
	 * @param roles
	 * @return
	 */
	private String getName(String username, Set<String> roles) {
		// service accounts are not in the userTable
		if (roles.contains(Role.CLIENT)) {
			return this.userTable.get(new UserId(username)).getName();
		} else {
			return null;
		}
	}

	/**
	 * Create dataset token
	 * 
	 * Only session-db calls this directly, everyone else must use it through
	 * SessionDbTokenResource.
	 * 
	 * This method signs dataset tokens with any values that it receives from the
	 * session-db. It's critical that the session-db makes requests to this
	 * method only after checking that user is allowed to create such token.
	 * 
	 * @param enduserUsername
	 * @param sessionId
	 * @param datasetId
	 * @param validString
	 * @param sc
	 * @return
	 */
	@POST
	@Path(PATH_DATASET_TOKEN)
	@RolesAllowed(Role.SESSION_DB) // only session-db should create DatasetTokens
	@Produces(MediaType.TEXT_PLAIN)
	public Response createDatasetToken(
			@QueryParam(QP_USERNAME) String enduserUsername,
			@QueryParam(QP_SESSION_ID) UUID sessionId,
			@QueryParam(QP_DATASET_ID) UUID datasetId,
			@QueryParam(QP_VALID) String validString,
			@Context SecurityContext sc) {

		String serviceUsername = sc.getUserPrincipal().getName();
		Instant valid = AuthTokens.parseValid(validString, DATASET_TOKEN_VALID_DEFAULT);

		if (serviceUsername == null) {
			// RolesAllowed prevents this
			throw new NotAuthorizedException("username is null");
		}

		String token = tokens.createDatasetToken(serviceUsername, enduserUsername, sessionId, datasetId, valid);

		return Response.ok(token).build();
	}

	/**
	 * Create session token
	 * 
	 * Only session-db calls this directly, everyone else must use it through
	 * SessionDbTokenResource.
	 * 
	 * This method signs dataset tokens with any values that it receives from the
	 * session-db. It's critical that the session-db makes requests to this
	 * method only after checking that user is allowed to create such token.
	 * 
	 * @param enduserUsername
	 * @param sessionId
	 * @param validString
	 * @param accessString
	 * @param sc
	 * @return
	 */
	@POST
	@Path(PATH_SESSION_TOKEN)
	@RolesAllowed(Role.SESSION_DB) // only session-db should create SessionTokens
	@Produces(MediaType.TEXT_PLAIN)
	public Response createSessionToken(
			@QueryParam(QP_USERNAME) String enduserUsername,
			@QueryParam(QP_SESSION_ID) UUID sessionId,
			@QueryParam(QP_VALID) String validString,
			@QueryParam(QP_ACCESS) String accessString,
			@Context SecurityContext sc) {

		String serviceUsername = sc.getUserPrincipal().getName();
		Instant valid = AuthTokens.parseValid(validString, SESSION_TOKEN_VALID_DEFAULT);

		Access access = null;

		if (accessString != null) {
			access = Access.valueOf(accessString);
		} else {
			access = Access.READ_ONLY;
		}

		if (serviceUsername == null) {
			// RolesAllowed prevents this
			throw new NotAuthorizedException("username is null");
		}

		String token = tokens.createSessionToken(serviceUsername, enduserUsername, sessionId, valid, access);

		return Response.ok(token).build();
	}
}
