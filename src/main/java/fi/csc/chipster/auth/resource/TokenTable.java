package fi.csc.chipster.auth.resource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;

public class TokenTable {

	private static final Duration CLIENT_TOKEN_LIFETIME = Duration.of(3, ChronoUnit.DAYS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK
	private static final Duration CLIENT_TOKEN_MAX_LIFETIME = Duration.of(10, ChronoUnit.DAYS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK

	private static final Duration SERVER_TOKEN_LIFETIME = Duration.of(6, ChronoUnit.HOURS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK;
	private static final Duration SERVER_TOKEN_MAX_LIFETIME = Duration.of(24, ChronoUnit.HOURS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK;

	private Timer cleanUpTimer;
	private static Duration CLEAN_UP_INTERVAL = Duration.of(30, ChronoUnit.MINUTES); // UNIT MUST BE DAYS OR SHORTER

	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	public TokenTable(HibernateUtil hibernate) {
		this.hibernate = hibernate;
		this.cleanUpTimer = new Timer("token db cleanup", true);
		this.cleanUpTimer.schedule(new TimerTask() {

			@Override
			public void run() {
				try {
					cleanUp();
				} catch (Exception e) {
					logger.warn("token clean up failed", e);
				}

			}
		}, 0, CLEAN_UP_INTERVAL.toMillis());
	}


	public Token createAndSaveToken(String username, HashSet<String> roles) {

		Token token = createToken(username, roles);

		getHibernate().session().save(token);
		
		return token;
	}

	public Token createToken(String username, HashSet<String> roles) {
		
		UUID tokenKey = RestUtils.createUUID();
		String rolesJson = RestUtils.asJson(roles);
		Instant now = Instant.now();

		Token token = new Token(username, tokenKey, now, now, rolesJson);
		token.setValidUntil(getTokenNextExpiration(token));
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
		Instant now = Instant.now();
		Instant clientOldestValidCreationTime = now.minus(CLIENT_TOKEN_MAX_LIFETIME);
		Instant serverOldestValidCreationTime = now.minus(SERVER_TOKEN_MAX_LIFETIME);

		logger.info("cleaning up expired tokens");
		logger.info("client token max lifetime is " + CLIENT_TOKEN_MAX_LIFETIME +
				", deleting client tokens created before " + clientOldestValidCreationTime);
		logger.info("server token max lifetime is " + SERVER_TOKEN_MAX_LIFETIME +
				" deleting server tokens created before " + serverOldestValidCreationTime);

		getHibernate().beginTransaction();
		List<Token> tokens = getHibernate().session().createQuery("from Token", Token.class).list();
		logger.info("before clean up db contains " + tokens.size() + " tokens");
		int deleteCount = 0;
		for (Token t: tokens) {

			// expired
			if (t.getValidUntil().isBefore(now)) {
				logger.info("deleting expired token " + t.getTokenKey() + " " + t.getUsername() + ", was valid until " + t.getValidUntil());
				getHibernate().session().delete(t);
				deleteCount++;
			} 

			// max lifetime reached
			else {
				boolean delete = false;
				if (t.getRoles().contains(Role.SERVER)) {
					if (t.getCreated().isBefore(serverOldestValidCreationTime)) {
						delete = true;
					}
				} else if (t.getCreated().isBefore(clientOldestValidCreationTime)) { 
					delete = true;
				}

				if (delete) {
					logger.info("deleting token " + t.getTokenKey() + " " + t.getUsername() + ", max life time reached, was created " + t.getCreated());
					getHibernate().session().delete(t);
					deleteCount++;
				}
			}
		}
		getHibernate().commit();
		logger.info("deleted " + deleteCount + " expired token(s) in " + Duration.between(begin, Instant.now()).toMillis() + " ms");
	}	

	public Token refreshToken(String token) {

		Token dbToken = getToken(token);

		failIfTokenExpired(dbToken);
		if (dbToken.getUsername().equals("comp")) {
			logger.info("REFRESH " + dbToken.getValidUntil() + " " + getTokenNextExpiration(dbToken));
		}

		dbToken.setValidUntil(getTokenNextExpiration(dbToken));
		
		return dbToken;		
	}


	public void delete(UUID uuid) {
		
		Token dbToken = getHibernate().session().get(Token.class, uuid);
		if (dbToken == null) {
			throw new NotFoundException();
		}
		getHibernate().session().delete(dbToken);
	}


	private void failIfTokenExpired(Token token) {
		if (!token.getValidUntil().isAfter(Instant.now())) {
			throw new ForbiddenException("token expired");
		}

		// token is (was) valid but token max lifetime may have been changed and could result in expiration
		// unlikely to happen if max lifetime change needs server restart as expired tokens are cleaned up at startup
		if (getTokenFinalExpiration(token).isBefore(Instant.now())) {
			throw new ForbiddenException("token expired, max lifetime reached");
		}
	}

	private Instant getTokenNextExpiration(Token token) {
		Instant nextCandidateExpiration = getTokenNextCandidateExpiration(token);
		Instant finalExpiration = getTokenFinalExpiration(token);

		return nextCandidateExpiration.isBefore(finalExpiration) ? nextCandidateExpiration: finalExpiration;
	}

	private Instant getTokenNextCandidateExpiration(Token token) {
		return token.getRoles().contains(Role.SERVER) ? 
				Instant.now().plus(SERVER_TOKEN_LIFETIME) : 
					Instant.now().plus(CLIENT_TOKEN_LIFETIME);

	}

	private Instant getTokenFinalExpiration(Token token) {
		return token.getRoles().contains(Role.SERVER) ? 
				token.getCreated().plus(SERVER_TOKEN_MAX_LIFETIME) : 
					token.getCreated().plus(CLIENT_TOKEN_MAX_LIFETIME);
	}


	public UUID parseUUID(String token) {
		try {
			return UUID.fromString(token);
		} catch (IllegalArgumentException e) {
			throw new NotAuthorizedException("token is not a valid UUID");
		}
	}

	public Token getToken(String tokenString) {
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
