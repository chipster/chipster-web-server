package fi.csc.chipster.sessiondb.resource;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;

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
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.SignatureException;

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

	private KeyPair jwsKeyPair;
	private SignatureAlgorithm signatureAlgorithm;

	private HibernateUtil hibernate;

	public SessionDbTokens(HibernateUtil hibernate, Config config) throws PEMException {
		this.hibernate = hibernate;
		
		this.signatureAlgorithm = JwsUtils.getSignatureAlgorithm(config, Role.SESSION_DB);
		this.jwsKeyPair = JwsUtils.getOrGenerateKeyPair(config, Role.SESSION_DB, signatureAlgorithm);
	}
	
	public SessionDbToken checkAuthorization(String tokenKey, UUID sessionId, UUID datasetId) {
    	return checkDatasetAuthorization(tokenKey, sessionId, datasetId, hibernate.session());
    }
	
	public SessionDbToken checkSessionAuthorization(String jwsString, UUID requestSessionId) {
		return checkSessionAuthorization(jwsString, requestSessionId, hibernate.session());
	}
	
	public SessionDbToken checkSessionAuthorization(String jwsString, UUID requestSessionId, org.hibernate.Session hibernateSession) {
		
		try {
			Jws<Claims> jws = Jwts.parser()
					.setSigningKey(jwsKeyPair.getPublic())
					.parseClaimsJws(jwsString);

			// now we can safely trust the JWT

		    UUID jwsSessionId = UUID.fromString(jws.getBody().get(CLAIM_KEY_SESSION_ID, String.class));
		    // null if this token is for the whole session
		    String jwsDatasetId = jws.getBody().get(CLAIM_KEY_DATASET_ID, String.class);
			
			Session session = hibernateSession.get(Session.class, requestSessionId);
			
			//TODO wouldn't these come anyway from the SessionResource?
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
			
			return new SessionDbToken(jwsString, jws.getBody().getSubject(), session, null, jws.getBody().getExpiration().toInstant());		     	    
		    
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

	
	public SessionDbToken checkDatasetAuthorization(String jwsString, UUID requestSessionId, UUID requestDatasetId, org.hibernate.Session hibernateSession) {
		
		try {
			Jws<Claims> jws = Jwts.parser()
					.setSigningKey(jwsKeyPair.getPublic())
					.parseClaimsJws(jwsString);

			// now we can safely trust the JWT

		    UUID jwsSessionId = UUID.fromString(jws.getBody().get(CLAIM_KEY_SESSION_ID, String.class));
		    // null if this token is for the whole session
		    String jwsDatasetId = jws.getBody().get(CLAIM_KEY_DATASET_ID, String.class);
		    
		    if (jwsDatasetId == null) {
		    	// this is a token for the whole session
		    	return checkSessionAuthorization(jwsString, requestSessionId, hibernateSession);
		    }
			
			Session session = hibernateSession.get(Session.class, requestSessionId);
			
			//TODO wouldn't these come anyway from the SessionResource?
			if (session == null) {
				throw new NotFoundException("session not found");
			}
			
			Dataset dataset = SessionDatasetResource.getDataset(requestSessionId, requestDatasetId, hibernateSession);
			
			if (dataset == null) {
				throw new NotFoundException("dataset not found");
			}
			
			if (!requestSessionId.equals(jwsSessionId)) {
				throw new ForbiddenException("token not valid for this session");
			}
						
			if (!requestDatasetId.equals(UUID.fromString(jwsDatasetId))) {
				throw new ForbiddenException("token not valid for this dataset");			
			}
			
			return new SessionDbToken(jwsString, jws.getBody().getSubject(), session, dataset, jws.getBody().getExpiration().toInstant());		     	    
		    
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

	public SessionDbToken createTokenForSession(String username, Session session, Instant valid) {
		
		if (session.getSessionId() == null) {
			throw new IllegalArgumentException("cannot create token for null session");
		}
		
		// null datasetId means access to the whole session
		return createToken(username, session, null, valid);
	}
		
	public SessionDbToken createTokenForDataset(String username, Session session, Dataset dataset, Instant valid) {
	
		if (session.getSessionId() == null) {
			throw new IllegalArgumentException("cannot create token for null session");
		}
		
		if (dataset.getDatasetId() == null) {
			throw new IllegalArgumentException("cannot create token for null dataset");
		}
	
		return createToken(username, session, dataset, valid);
	}
	
	private SessionDbToken createToken(String username, Session session, Dataset dataset, Instant valid) {
		
		Instant now = Instant.now();
		
		UUID datasetId = dataset != null ? dataset.getDatasetId() : null;
		
		String jws = Jwts.builder()
			    .setIssuer(Role.SESSION_DB)
			    .setSubject(username)
			    .setAudience(Role.SESSION_DB)
			    .setExpiration(Date.from(valid))
			    .setNotBefore(Date.from(now))
			    .setIssuedAt(Date.from(now))
			    .setId(UUID.randomUUID().toString())
			    .claim(CLAIM_KEY_SESSION_ID, session.getSessionId())
			    .claim(CLAIM_KEY_DATASET_ID, datasetId)
			    .signWith(jwsKeyPair.getPrivate(), signatureAlgorithm)			  
			    .compact();
		
		//TODO wouldn't sessionId and datasetId be enough?
		return new SessionDbToken(jws, username, session, dataset, valid);
	}
}
