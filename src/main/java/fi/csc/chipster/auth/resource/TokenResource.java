package fi.csc.chipster.auth.resource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.DELETE;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("tokens")
public class TokenResource {

	public static final String TOKENS = "tokens";

	private static final String TOKEN_HEADER = "chipster-token";

	private static final long CLIENT_TOKEN_LIFETIME = 3;
	private static final TemporalUnit CLIENT_TOKEN_LIFETIME_UNIT = ChronoUnit.DAYS; // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK
	
	private static final long CLIENT_TOKEN_MAX_LIFETIME = 10;
	private static final TemporalUnit CLIENT_TOKEN_MAX_LIFETIME_UNIT = ChronoUnit.DAYS; // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK

	private static final long SERVER_TOKEN_LIFETIME = 30;
	private static final TemporalUnit SERVER_TOKEN_LIFETIME_UNIT = ChronoUnit.DAYS; // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK

	private static final long SERVER_TOKEN_MAX_LIFETIME = 90;
	private static final TemporalUnit SERVER_TOKEN_MAX_LIFETIME_UNIT = ChronoUnit.DAYS; // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK
	
	private Timer cleanUpTimer;
	private static int CLEAN_UP_INTERVAL = 1000*60*30; // ms
	
	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	// notifications
	//    @GET
	//    @Path("{id}/events")
	//    @Produces(SseFeature.SERVER_SENT_EVENTS)
	//    public EventOutput listenToBroadcast(@PathParam("id") String id, @QueryParam("username") String username) {
	//		Hibernate.beginTransaction();
	//		checkReadAuthorization(username, id);
	//		Hibernate.commit();
	//        return Events.getEventOutput();
	//    }

	public TokenResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
		this.cleanUpTimer = new Timer("token db cleanup", true);
		this.cleanUpTimer.schedule(new TimerTask() {
			
			@Override
			public void run() {
				cleanUp();
				
			}
		}, 0, CLEAN_UP_INTERVAL);
	}

	@POST
	@RolesAllowed(Role.PASSWORD)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response createToken(@Context SecurityContext sc) {

		// curl -i -H "Content-Type: application/json" --user client:clientPassword -X POST http://localhost:8081/auth/tokens

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();

		String username = sc.getUserPrincipal().getName();

		if (username == null) {
			// RolesAllowed prevents this
			throw new NotAuthorizedException("username is null");
		}

		Token token = createToken(username, principal.getRoles());

		getHibernate().session().save(token);

		return Response.ok(token).build();
	}

	public Token createToken(String username, HashSet<String> roles) {
		//FIXME has to be cryptographically secure
		UUID tokenKey = RestUtils.createUUID();
		String rolesJson = RestUtils.asJson(roles);
		LocalDateTime now = LocalDateTime.now();

		Token token = new Token(username, tokenKey, now, now, rolesJson);
		token.setValid(getTokenNextExpiration(token));
		return token;
	}


	/**
	 * Clean up expired tokens from db
	 * 
	 * TODO Could be done using hql only
	 * 
	 */
	private void cleanUp() {
		Instant begin = Instant.now();
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime clientOldestValidCreationTime = now.minus(Duration.of(CLIENT_TOKEN_MAX_LIFETIME, CLIENT_TOKEN_MAX_LIFETIME_UNIT));
		LocalDateTime serverOldestValidCreationTime = now.minus(Duration.of(SERVER_TOKEN_MAX_LIFETIME, SERVER_TOKEN_MAX_LIFETIME_UNIT));

		logger.info("cleaning up expired tokens");
		logger.info("client token max lifetime is " + CLIENT_TOKEN_MAX_LIFETIME + " " + CLIENT_TOKEN_MAX_LIFETIME_UNIT.toString() +
				", deleting client tokens created before " + clientOldestValidCreationTime);
		logger.info("server token max lifetime is " + SERVER_TOKEN_MAX_LIFETIME + " " + SERVER_TOKEN_MAX_LIFETIME_UNIT.toString() +
				" deleting server tokens created before " + serverOldestValidCreationTime);
		
		getHibernate().beginTransaction();
		List<Token> tokens = getHibernate().session().createQuery("from Token", Token.class).list();
		int deleteCount = 0;
		for (Token t: tokens) {

			// expired
			if (t.getValid().isBefore(now)) {
				logger.info("deleting expired token " + t.getTokenKey() + " " + t.getUsername() + ", was valid until " + t.getValid());
				getHibernate().session().delete(t);
				deleteCount++;
			} 
			
			// max lifetime reached
			else {
				boolean delete = false;
				if (t.getRoles().contains(Role.SERVER)) {
					if (t.getCreationTime().isBefore(serverOldestValidCreationTime)) {
						delete = true;
					}
				} else if (t.getCreationTime().isBefore(clientOldestValidCreationTime)) { 
					delete = true;
				}

				if (delete) {
					logger.info("deleting token " + t.getTokenKey() + " " + t.getUsername() + ", max life time reached, was created " + t.getCreationTime());
					getHibernate().session().delete(t);
					deleteCount++;
				}
			}
		}
		getHibernate().commit();
		logger.info("deleted " + deleteCount + " expired token(s) in " + Duration.between(begin, Instant.now()).toMillis() + " ms");
	}

	
	@GET
	@RolesAllowed(Role.SERVER)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response checkToken(@HeaderParam(TOKEN_HEADER) String requestToken, @Context SecurityContext sc) {

		Token dbToken = getToken(requestToken);

		if (dbToken.getValid().isAfter(LocalDateTime.now())) {
			return Response.ok(dbToken).build();
		} else {
			// not a ForbiddenException because the server's token was authenticated correctly in the TokenRequestFilter 
			throw new NotFoundException("token expired");
		}				
	}	

	@POST
	@Path("{refresh}")
	@RolesAllowed({Role.CLIENT, Role.SERVER})
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response refreshToken(@Context SecurityContext sc) {

		String token = ((AuthPrincipal) sc.getUserPrincipal()).getTokenKey();
		Token dbToken = getToken(token);
		
		failIfTokenExpired(dbToken);
		dbToken.setValid(getTokenNextExpiration(dbToken));

		return Response.ok(dbToken).build();
	}


	@DELETE
	@RolesAllowed(Role.CLIENT)
	@Transaction
	public Response delete(@Context SecurityContext sc) {

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();

		UUID uuid = parseUUID(principal.getTokenKey());

		Token dbToken = getHibernate().session().get(Token.class, uuid);
		if (dbToken == null) {
			throw new NotFoundException();
		}
		getHibernate().session().delete(dbToken);

		return Response.noContent().build();
	}


	private void failIfTokenExpired(Token token) {
		if (!token.getValid().isAfter(LocalDateTime.now())) {
			throw new ForbiddenException("token expired");
		}

		// token is (was) valid but token max lifetime may have been changed and could result in expiration
		if (token.getRoles().contains(Role.SERVER) &&
				token.getValid().isAfter(token.getCreationTime().plus(SERVER_TOKEN_MAX_LIFETIME, SERVER_TOKEN_MAX_LIFETIME_UNIT)) ||
				token.getRoles().contains(Role.CLIENT) &&
				token.getValid().isAfter(token.getCreationTime().plus(CLIENT_TOKEN_MAX_LIFETIME, CLIENT_TOKEN_MAX_LIFETIME_UNIT))) {
			throw new ForbiddenException("token expired");
		}
	}

	private LocalDateTime getTokenNextExpiration(Token token) {
		LocalDateTime nextCandidateExpiration = getTokenNextCandidateExpiration(token);
		LocalDateTime finalExpiration = getTokenFinalExpiration(token);
		
		return nextCandidateExpiration.isBefore(finalExpiration) ? nextCandidateExpiration: finalExpiration;
	}

	private LocalDateTime getTokenNextCandidateExpiration(Token token) {
		return token.getRoles().contains(Role.SERVER) ? 
				LocalDateTime.now().plus(SERVER_TOKEN_LIFETIME, SERVER_TOKEN_LIFETIME_UNIT) : 
					LocalDateTime.now().plus(CLIENT_TOKEN_LIFETIME, CLIENT_TOKEN_LIFETIME_UNIT);
		
	}
	
	private LocalDateTime getTokenFinalExpiration(Token token) {
		return token.getRoles().contains(Role.SERVER) ? 
				token.getCreationTime().plus(SERVER_TOKEN_MAX_LIFETIME, SERVER_TOKEN_MAX_LIFETIME_UNIT) : 
					token.getCreationTime().plus(CLIENT_TOKEN_MAX_LIFETIME, CLIENT_TOKEN_MAX_LIFETIME_UNIT);
	}
	
	
	private UUID parseUUID(String token) {
		try {
			return UUID.fromString(token);
		} catch (IllegalArgumentException e) {
			throw new NotAuthorizedException("token is not a valid UUID");
		}
	}

	private Token getToken(String tokenString) {
		if (tokenString == null) {
			throw new NotFoundException("chipster-token header is null");
		}

		UUID uuid = parseUUID(tokenString);

		Token dbToken = getHibernate().session().get(Token.class, uuid);

		if (dbToken == null) {
			throw new NotFoundException("token not found");
		}

		return dbToken;
	}

	private HibernateUtil getHibernate() {
		return hibernate;
	}
}
