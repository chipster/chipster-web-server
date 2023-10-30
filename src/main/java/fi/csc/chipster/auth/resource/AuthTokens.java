package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.ChipsterToken;
import fi.csc.chipster.auth.model.DatasetToken;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.SessionToken;
import fi.csc.chipster.auth.model.SessionToken.Access;
import fi.csc.chipster.auth.model.UserToken;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureAlgorithm;
import io.jsonwebtoken.security.SignatureException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;

/**
 * Create and validate Chipster tokens
 * 
 * Tokens are signed with auth's key pair. Validation ensures that the token is signed
 * by the auth service, hasn't been tampered with and is still valid.
 * 
 * Otherwise it's up to other services to decide what kind of tokens they allow to
 * be created and what kind of access is allowed with those tokens.
 * 
 * 
 * @author klemela
 *
 */
public class AuthTokens {
	
	public static final String CLAIM_KEY_CLASS = "class";
	
	public static final String CLAIM_KEY_ROLES = "roles";
	public static final String CLAIM_KEY_NAME = "name";
	public static final String CLAIM_KEY_LOGIN_TIME = "loginTime";

	public static final String CLAIM_SERVICE = "service";
	public static final String CLAIM_KEY_SESSION_ID = "sessionId";
	public static final String CLAIM_KEY_DATASET_ID = "datasetId";
	public static final String CLAIM_KEY_ACCESS = "readWrite";
	
	private static final Duration CLIENT_TOKEN_LIFETIME = Duration.of(3, ChronoUnit.DAYS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK
	private static final Duration CLIENT_TOKEN_MAX_LIFETIME = Duration.of(10, ChronoUnit.DAYS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK
	
	private static final Duration SERVER_TOKEN_LIFETIME = Duration.of(6, ChronoUnit.HOURS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK;
	private static final Duration SERVER_TOKEN_MAX_LIFETIME = Duration.of(10, ChronoUnit.DAYS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK;
	public static final String ROLES_DELIMITER = " ";
			
	private static Logger logger = LogManager.getLogger();
	
	public SignatureAlgorithm signatureAlgorithm;	
	private KeyPair jwsKeyPair;

	public AuthTokens(Config config) throws IOException {
				
		this.signatureAlgorithm = JwsUtils.getSignatureAlgorithm(config, Role.AUTH);
		this.jwsKeyPair = JwsUtils.getOrGenerateKeyPair(config, Role.AUTH, signatureAlgorithm);
	}
	
	public String getPublicKey() {
		
		return JwsUtils.publicKeyToPem(jwsKeyPair.getPublic());
	}
	
	
	public String createNewUserToken(String username, Set<String> roles, String name) {

		Instant now = Instant.now();
		
		return createUserToken(username, roles, now, name);		
	}

	private String createUserToken(String username, Set<String> roles, Instant loginTime, String name) {
		return createUserToken(username, roles, loginTime, jwsKeyPair.getPrivate(), signatureAlgorithm, name);
	}
	
	public static String createUserToken(String username, Set<String> roles, Instant loginTime, PrivateKey privateKey, SignatureAlgorithm signatureAlgorithm, String name) {
				
		String rolesString = String.join(ROLES_DELIMITER, roles);
		
		Instant now = Instant.now();
		Instant expiration = getUserTokenNextExpiration(now, roles);
		
		String jws = Jwts.builder()
			    .issuer("chipster")
			    .subject(username)
			    .audience().add("chipster").and()
			    .expiration(Date.from(expiration))
			    .notBefore(Date.from(now)) 
			    .issuedAt(Date.from(now))
			    .claim(CLAIM_KEY_CLASS, UserToken.class.getName())
			    .claim(CLAIM_KEY_NAME, name)
			    .claim(CLAIM_KEY_ROLES, rolesString)
			    .claim(CLAIM_KEY_LOGIN_TIME, loginTime.getEpochSecond())
			    .signWith(privateKey, signatureAlgorithm)			  
			    .compact();		     
	    
	    return jws;
	}
	
	public String createSessionToken(String issuer, String username, UUID sessionId, Instant valid, Access access) {
		
		Instant now = Instant.now();
		
		String jws = Jwts.builder()
			    .issuer(issuer)
			    .subject(username)
			    .audience().add("chipster").and()
			    .expiration(Date.from(valid))
			    .notBefore(Date.from(now))
			    .issuedAt(Date.from(now))
			    .id(UUID.randomUUID().toString())
			    .claim(CLAIM_KEY_CLASS, SessionToken.class.getName())
			    .claim(CLAIM_KEY_SESSION_ID, sessionId)
			    .claim(CLAIM_KEY_ACCESS, access.name())
			    .signWith(jwsKeyPair.getPrivate(), signatureAlgorithm)			  
			    .compact();
	    
	    return jws;
	}

	
	public String createDatasetToken(String issuer, String username, UUID sessionId, UUID datasetId, Instant valid) {
				
		Instant now = Instant.now();
		
		String jws = Jwts.builder()
			    .issuer(issuer)
			    .subject(username)
			    .audience().add("chipster").and()
			    .expiration(Date.from(valid))
			    .notBefore(Date.from(now))
			    .issuedAt(Date.from(now))
			    .id(UUID.randomUUID().toString())
			    .claim(CLAIM_KEY_CLASS, DatasetToken.class.getName())
			    .claim(CLAIM_KEY_SESSION_ID, sessionId)
			    .claim(CLAIM_KEY_DATASET_ID, datasetId)
			    .signWith(jwsKeyPair.getPrivate(), signatureAlgorithm)			  
			    .compact();
	    
	    return jws;
	}

	public String refreshUserToken(String token, String name) {

		// throws if fails
		UserToken validToken = validateUserToken(token);
		
		// debug logging?
		if (validToken.getUsername().equals("comp")) {
			logger.info("REFRESH " + validToken.getValidUntil() + " " + getUserTokenNextExpiration(validToken.getCreated(), validToken.getRoles()));
		}
		
		return createUserToken(validToken.getUsername(), validToken.getRoles(), validToken.getCreated(), name);
	}
	
	public static Instant getUserTokenNextExpiration(Instant created, Set<String> roles) {
		Instant nextCandidateExpiration = getUserTokenNextCandidateExpiration(roles);
		Instant finalExpiration = getUserTokenFinalExpiration(created, roles);

		return nextCandidateExpiration.isBefore(finalExpiration) ? nextCandidateExpiration: finalExpiration;
	}

	private static Instant getUserTokenNextCandidateExpiration(Set<String> roles) {
		return roles.contains(Role.SERVER) ? 
				Instant.now().plus(SERVER_TOKEN_LIFETIME) : 
					Instant.now().plus(CLIENT_TOKEN_LIFETIME);

	}

	private static Instant getUserTokenFinalExpiration(Instant created, Set<String> roles) {
		return roles.contains(Role.SERVER) ? 
				created.plus(SERVER_TOKEN_MAX_LIFETIME) : 
					created.plus(CLIENT_TOKEN_MAX_LIFETIME);
	}

	public UserToken validateUserToken(String jwsString) {
		
		return AuthTokens.validateUserToken(jwsString, jwsKeyPair.getPublic());
	}
	
	public static UserToken validateUserToken(String jwsString, PublicKey publicKey) {
		
		return validateToken(jwsString, publicKey, UserToken.class);
	}
	
	public static Jws<Claims> validateSignature(String jwsString, PublicKey publicKey) {
		
		try {
			Jws<Claims> jws = Jwts.parser()
					.verifyWith(publicKey)
					.build()
					.parseSignedClaims(jwsString);
		
			// now we can safely trust the JWT
			return jws;		   		    
		    
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
	
	public static <T extends ChipsterToken> boolean isTokenClass(Claims body, Class<T> tokenClass) {
		return body.get(CLAIM_KEY_CLASS) != null 
				&& body.get(CLAIM_KEY_CLASS).toString().equals(tokenClass.getName());
	}
		
	@SuppressWarnings("unchecked")
	public static <T extends ChipsterToken> T validateToken(String jwsString, PublicKey publicKey, Class<T> tokenClass) {
		
		Jws<Claims> jws = validateSignature(jwsString, publicKey);
		Claims body = jws.getPayload();
		
		// now we can safely trust the JWT
		if (!isTokenClass(body, tokenClass)) {
			throw new ForbiddenException("token passed validation, but isn't an "  + tokenClass.getName());
		}		
		
		if (UserToken.class.equals(tokenClass)) {
			return (T) claimsToUserToken(body, jwsString);
			
		} else if (SessionToken.class.equals(tokenClass)) {
			return (T) claimsToSessionToken(body, jwsString);
			
		} else if (DatasetToken.class.equals(tokenClass)) {
			return (T) claimsToDatasetToken(body, jwsString);
					
		} else {
			logger.error("unknown token type " + tokenClass);
			throw new ForbiddenException("unknown token type");
		}		     	    
	}
		
	/**
	 * Decode jwt token without checking its signature
	 * 
	 * Client can trust the token it got from the server. Servers should always validate them.
	 * 
	 * @param jwsString
	 * @return
	 */
	public static UserToken decodeUserToken(String jwsString) {
		
		// signature must be removed, jjwt refuses to decode signed tokens without validating them 
		String jwtString = jwsToJwt(jwsString);
		
		try {
			Jwt<Header, Claims> jwt = Jwts.parser().unsecured().build()
					.parseUnsecuredClaims(jwtString);

			if (!isTokenClass(jwt.getPayload(), UserToken.class)) {
				throw new IllegalStateException("token isn't an " + UserToken.class.getName());
			}

			return claimsToUserToken(jwt.getPayload(), jwsString);		     
		   		    
		    
		} catch (ExpiredJwtException e) {
			// 401 https://stackoverflow.com/questions/45153773/correct-http-code-for-authentication-token-expiry-401-or-403
			throw new NotAuthorizedException("token expired");
			
		} catch (IllegalArgumentException e) {
			throw new NotAuthorizedException("token is null, empty or only whitespace");
			
		} catch (MalformedJwtException e) {
			throw new NotAuthorizedException("invalid token: " + e.getMessage());		    		    
		    
		} catch (JwtException e) {
		    logger.warn("jws validation failed", e.getMessage());
		    throw new NotAuthorizedException("token not valid");
		}		
	}
	
	public static String getPayload(String jws) {
	    
	    int payloadStartIndex = jws.indexOf(".") + 1;
	    int payloadEndIndex = jws.lastIndexOf(".");
	    
	    String payload = jws.substring(payloadStartIndex, payloadEndIndex);
	    return payload;
	}

	public static String jwsToJwt(String jws) {
	    	    
	    String payload = getPayload(jws);
	    String noneHeader = Base64.getEncoder().encodeToString("{\"alg\": \"none\"}".getBytes());
	    	     
		return noneHeader + "." + payload + ".";
	}

	public static UserToken claimsToUserToken(Claims body, String jwsString) {
		Set<String> roles = new HashSet<String>(Arrays.asList(body.get(CLAIM_KEY_ROLES).toString().split(ROLES_DELIMITER)));
	    Instant loginTime = Instant.ofEpochSecond(Long.parseLong(body.get(CLAIM_KEY_LOGIN_TIME).toString()));
	     
	    UserToken token = new UserToken();
		token.setUsername(body.getSubject());  
	    token.setValidUntil(body.getExpiration().toInstant()); 
	    token.setCreated(loginTime); 
	    token.setRoles(roles);
	    
	    return token;
	}
	
	public static SessionToken claimsToSessionToken(Claims body, String jwsString) {
				
		// auth doesn't care about sessionId and datasetId, session-db must check those
		String jwsSessionIdString = body.get(CLAIM_KEY_SESSION_ID, String.class);
		UUID jwsSessionId = UUID.fromString(jwsSessionIdString);
			    
	    String accessString = body.get(CLAIM_KEY_ACCESS, String.class);
	    Access access = Access.valueOf(accessString);
	    			
		return new SessionToken(body.getSubject(), jwsSessionId, body.getExpiration().toInstant(), access);		     	    
	}
	
	public static DatasetToken claimsToDatasetToken(Claims body, String jwsString) {
				
		// auth doesn't care about sessionId and datasetId, session-db must check those
		String jwsSessionIdString = body.get(CLAIM_KEY_SESSION_ID, String.class);
		UUID jwsSessionId = UUID.fromString(jwsSessionIdString);
		
	    String jwsDatasetIdString = body.get(CLAIM_KEY_DATASET_ID, String.class);
	    UUID jwsDatasetId = UUID.fromString(jwsDatasetIdString);
	    			
		return new DatasetToken(body.getSubject(), jwsSessionId, jwsDatasetId, body.getExpiration().toInstant());		     	    
	}
	
	public static Instant parseValid(String validString) {

		if (validString != null) {
			try {
				return Instant.parse(validString);
			} catch (DateTimeParseException e) {
				logger.error("query parameter 'valid' can't be parsed to Instant", e);
				throw new BadRequestException("query parameter 'valid' can't be parsed to Instant");
			}
		}
		return null;
	}
	
	public static Instant parseValid(String validString, long defaultSeconds) {

		if (validString == null) {
			return Instant.now().plus(Duration.ofSeconds(defaultSeconds));
		} else {
			return parseValid(validString);
		}
	}
}
