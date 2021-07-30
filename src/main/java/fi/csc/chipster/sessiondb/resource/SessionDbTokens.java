package fi.csc.chipster.sessiondb.resource;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.openssl.PEMException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.JwsUtils;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionDbToken;
import fi.csc.chipster.sessiondb.model.SessionDbToken.Access;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.SignatureException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

/**
 * Limited tokens for session-db
 * 
 * When a url is used for something that can't set the Authorization header
 * (e.g. browser WebSocket client, file downloads and external visualization libraries)
 * we must include the authentiation information in the URL where it's more visible to
 * the user and in server logs.
 *
 * If we would use the user's own token in the URL, the user might share
 * this dataset URL without realizing that the token gives access to user's all
 * sessions.
 *
 * We can Use these limited tokens instead that are valid only for read-only operation either
 * for one session or one dataset and only for a limited time.
 * 
 * 
 * @author klemela
 *
 */
public class SessionDbTokens {

	public static final Logger logger = LogManager.getLogger();
	
	public static final String CLAIM_KEY_SESSION_ID = "sessionId";
	public static final String CLAIM_KEY_DATASET_ID = "datasetId";
	public static final String CLAIM_KEY_ACCESS = "readWrite";

	private KeyPair jwsKeyPair;
	private SignatureAlgorithm signatureAlgorithm;

	private HibernateUtil hibernate;

	public SessionDbTokens(HibernateUtil hibernate, Config config) throws PEMException {
		this.hibernate = hibernate;
		
		this.signatureAlgorithm = JwsUtils.getSignatureAlgorithm(config, Role.SESSION_DB);
		this.jwsKeyPair = JwsUtils.getOrGenerateKeyPair(config, Role.SESSION_DB, signatureAlgorithm);
	}
	
	public Dataset checkAuthorization(String jwsString, UUID sessionId, UUID datasetId, boolean requireReadWrite) {
		
		SessionDbToken token = validate(jwsString);
		
    	return checkDatasetAuthorization(token, sessionId, datasetId, requireReadWrite, hibernate.session());
    }
	
	public Session checkSessionAuthorization(String jwsString, UUID requestSessionId, boolean requireReadWrite) {
		
		SessionDbToken token = validate(jwsString);
		
		return checkSessionAuthorization(token, requestSessionId, requireReadWrite, hibernate.session());
	}
	
	public SessionDbToken validate(String jwsString) {
		
		try {
			Jws<Claims> jws = Jwts.parserBuilder()
					.setSigningKey(jwsKeyPair.getPublic())
					.build()
					.parseClaimsJws(jwsString);

			// now we can safely trust the JWT

		    UUID jwsSessionId = UUID.fromString(jws.getBody().get(CLAIM_KEY_SESSION_ID, String.class));
		    // null if this token is for the whole session
		    String jwsDatasetIdString = jws.getBody().get(CLAIM_KEY_DATASET_ID, String.class);
		    UUID jwsDatasetId = null;
		    if (jwsDatasetIdString != null) {
		    	jwsDatasetId = UUID.fromString(jwsDatasetIdString);
		    }
		    
		    String accessString = jws.getBody().get(CLAIM_KEY_ACCESS, String.class);
		    Access access = Access.valueOf(accessString);
						
			return new SessionDbToken(jwsString, jws.getBody().getSubject(), jwsSessionId, jwsDatasetId, jws.getBody().getExpiration().toInstant(), access);		     	    
		    
		} catch (ExpiredJwtException e) {
			// 401 https://stackoverflow.com/questions/45153773/correct-http-code-for-authentication-token-expiry-401-or-403
			throw new NotAuthorizedException("token expired");
			
		} catch (SignatureException e) {
			throw new ForbiddenException("invalid token signature");
			
		} catch (IllegalArgumentException e) {
			throw new NotAuthorizedException("token is null, empty or only whitespace");
			
		} catch (MalformedJwtException e) {
			throw new NotAuthorizedException("invalid token: " + e.getMessage());		    		    
		    
		} catch (JwtException e) {
		    logger.warn("jws validation failed", e.getMessage());
		    throw new NotAuthorizedException("token not valid");
		}
	}
	
	/**
	 * Check that the SessionDbToken is valid for requested session
	 * 
	 * This assumes that the JWT has been already safely parsed and validated to a SessionDbToken.
	 * 
	 * @param token
	 * @param requestSessionId
	 * @param requireReadWrite 
	 * @param hibernateSession
	 * @return 
	 */
	public Session checkSessionAuthorization(SessionDbToken token, UUID requestSessionId, boolean requireReadWrite, org.hibernate.Session hibernateSession) {

	    UUID jwsSessionId = token.getSessionId();
	    // null if this token is for the whole session
	    UUID jwsDatasetId = token.getDatasetId();
		
		Session session = hibernateSession.get(Session.class, requestSessionId);
		
		// sanity check although SessionResoure probably has checked this already
		if (session == null) {
			throw new NotFoundException("session not found");
		}
					
		if (!requestSessionId.equals(jwsSessionId)) {
			throw new ForbiddenException("token not valid for this session");
		}
		
		if (jwsDatasetId != null) {
			// this token is only for single dataset
			throw new NotAuthorizedException("not a session token");
		}
		
		if (requireReadWrite && Access.READ_WRITE != token.getAccess()) {
			throw new ForbiddenException("no read-write access with this token");
		}
		
		return session;
	}

	
	/**
	 * 
	 * Check that the SessionDbToken is valid for requested session and dataset
	 * 
	 * This assumes that the JWT has been already safely parsed and validated to a SessionDbToken.
	 * 
	 * @param token
	 * @param requestSessionId
	 * @param requestDatasetId
	 * @param requireReadWrite 
	 * @param hibernateSession
	 * @return 
	 */
	public Dataset checkDatasetAuthorization(SessionDbToken token, UUID requestSessionId, UUID requestDatasetId, boolean requireReadWrite, org.hibernate.Session hibernateSession) {
		
		UUID jwsSessionId = token.getSessionId();
		// null if this token is for the whole session
		UUID jwsDatasetId = token.getDatasetId();

		Session session = null;
		if (jwsDatasetId == null) {
			// this is a token for the whole session
			session = checkSessionAuthorization(token, requestSessionId, requireReadWrite, hibernateSession);
			
		} else {
		
			if (requireReadWrite) {
				// no need for the writing with dataset tokens so far
				throw new ForbiddenException("dataset tokens are read-only");
			}
	
			session = hibernateSession.get(Session.class, requestSessionId);
			
			// sanity check although SessionResoure probably has checked this already
			if (session == null) {
				throw new NotFoundException("session not found");
			}
			
			if (!requestDatasetId.equals(jwsDatasetId)) {
				throw new ForbiddenException("token not valid for this dataset");			
			}
		}

		Dataset dataset = SessionDatasetResource.getDataset(requestSessionId, requestDatasetId, hibernateSession);

		if (dataset == null) {
			throw new NotFoundException("dataset not found");
		}

		if (!requestSessionId.equals(jwsSessionId)) {
			throw new ForbiddenException("token not valid for this session");
		}
		
		return dataset;
	}

	public SessionDbToken createTokenForSession(String username, Session session, Instant valid, Access access) {
		
		if (session.getSessionId() == null) {
			throw new IllegalArgumentException("cannot create token for null session");
		}
		
		// null datasetId means access to the whole session
		return createToken(username, session.getSessionId(), null, valid, access);
	}
		
	public SessionDbToken createTokenForDataset(String username, Session session, Dataset dataset, Instant valid, Access access) {
	
		if (session.getSessionId() == null) {
			throw new IllegalArgumentException("cannot create token for null session");
		}
		
		if (dataset.getDatasetId() == null) {
			throw new IllegalArgumentException("cannot create token for null dataset");
		}
	
		return createToken(username, session.getSessionId(), dataset.getDatasetId(), valid, access);
	}
	
	private SessionDbToken createToken(String username, UUID sessionId, UUID datasetId, Instant valid, Access access) {
		
		Instant now = Instant.now();
		
		String jws = Jwts.builder()
			    .setIssuer(Role.SESSION_DB)
			    .setSubject(username)
			    .setAudience(Role.SESSION_DB)
			    .setExpiration(Date.from(valid))
			    .setNotBefore(Date.from(now))
			    .setIssuedAt(Date.from(now))
			    .setId(UUID.randomUUID().toString())
			    .claim(CLAIM_KEY_SESSION_ID, sessionId)
			    .claim(CLAIM_KEY_DATASET_ID, datasetId)
			    .claim(CLAIM_KEY_ACCESS, access.name())
			    .signWith(jwsKeyPair.getPrivate(), signatureAlgorithm)			  
			    .compact();
		
		return new SessionDbToken(jws, username, sessionId, datasetId, valid, access);
	}
}
