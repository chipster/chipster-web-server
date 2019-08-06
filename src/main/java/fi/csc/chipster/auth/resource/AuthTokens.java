package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.security.Key;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.ForbiddenException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.ParsedToken;
import fi.csc.chipster.auth.model.Role;
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
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.SignatureException;

public class AuthTokens {
	
	public static final String CLAIM_KEY_CLASS = "class";
	public static final String CLAIM_KEY_ROLES = "roles";
	public static final String CLAIM_KEY_NAME = "name";
	public static final String CLAIM_KEY_LOGIN_TIME = "loginTime";
	
	public static final String CLAIM_VALUE_CLASS = AuthTokens.class.getName();
	
	private static final Duration CLIENT_TOKEN_LIFETIME = Duration.of(3, ChronoUnit.DAYS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK
	private static final Duration CLIENT_TOKEN_MAX_LIFETIME = Duration.of(10, ChronoUnit.DAYS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK
	
	private static final Duration SERVER_TOKEN_LIFETIME = Duration.of(6, ChronoUnit.HOURS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK;
	private static final Duration SERVER_TOKEN_MAX_LIFETIME = Duration.of(10, ChronoUnit.DAYS); // UNIT MUST BE DAYS OR SHORTER, MONTH IS NOT OK;
	private static final String ROLES_DELIMITER = " ";
		
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
	
	
	public String createNewToken(String username, Set<String> roles, String name) {

		Instant now = Instant.now();
		
		return createToken(username, roles, now, name);		
	}

	private String createToken(String username, Set<String> roles, Instant loginTime, String name) {
		return createToken(username, roles, loginTime, jwsKeyPair.getPrivate(), signatureAlgorithm, name);
	}
	
	public static String createToken(String username, Set<String> roles, Instant loginTime, Key privateKey, SignatureAlgorithm signatureAlgorithm, String name) {
				
		String rolesString = String.join(ROLES_DELIMITER, roles);
		
		Instant now = Instant.now();
		Instant expiration = getTokenNextExpiration(now, roles);
		
		String jws = Jwts.builder()
			    .setIssuer("chipster")
			    .setSubject(username)
			    .setAudience("chipster")
			    .setExpiration(Date.from(expiration))
			    .setNotBefore(Date.from(now)) 
			    .setIssuedAt(Date.from(now))
			    .claim(CLAIM_KEY_CLASS, CLAIM_VALUE_CLASS)
			    .claim(CLAIM_KEY_NAME, name)
			    .claim(CLAIM_KEY_ROLES, rolesString)
			    .claim(CLAIM_KEY_LOGIN_TIME, loginTime.getEpochSecond())
			    .signWith(privateKey, signatureAlgorithm)			  
			    .compact();		     
	    
	    return jws;
	}

	public String refreshToken(String token, String name) {

		// throws if fails
		ParsedToken validToken = validateToken(token);
		
		// debug logging?
		if (validToken.getUsername().equals("comp")) {
			logger.info("REFRESH " + validToken.getValidUntil() + " " + getTokenNextExpiration(validToken.getCreated(), validToken.getRoles()));
		}
		
		return createToken(validToken.getUsername(), validToken.getRoles(), validToken.getCreated(), name);
	}
	
	private static Instant getTokenNextExpiration(Instant created, Set<String> roles) {
		Instant nextCandidateExpiration = getTokenNextCandidateExpiration(roles);
		Instant finalExpiration = getTokenFinalExpiration(created, roles);

		return nextCandidateExpiration.isBefore(finalExpiration) ? nextCandidateExpiration: finalExpiration;
	}

	private static Instant getTokenNextCandidateExpiration(Set<String> roles) {
		return roles.contains(Role.SERVER) ? 
				Instant.now().plus(SERVER_TOKEN_LIFETIME) : 
					Instant.now().plus(CLIENT_TOKEN_LIFETIME);

	}

	private static Instant getTokenFinalExpiration(Instant created, Set<String> roles) {
		return roles.contains(Role.SERVER) ? 
				created.plus(SERVER_TOKEN_MAX_LIFETIME) : 
					created.plus(CLIENT_TOKEN_MAX_LIFETIME);
	}

	public ParsedToken validateToken(String jwsString) {
		
		return AuthTokens.validate(jwsString, jwsKeyPair.getPublic());
	}
	
	public static ParsedToken validate(String jwsString, PublicKey publicKey) {
		
		try {
			Jws<Claims> jws = Jwts.parser()
					.setSigningKey(publicKey)
					.parseClaimsJws(jwsString);

			// now we can safely trust the JWT
			if (jws.getBody().get(CLAIM_KEY_CLASS) == null || !jws.getBody().get(CLAIM_KEY_CLASS).toString().equals(CLAIM_VALUE_CLASS)) {
				throw new IllegalStateException("token passed validation, but isn't an "  + CLAIM_VALUE_CLASS + ". Are SessionDbTokens using the same keys?");
			}

			return claimsToParsedToken(jws.getBody(), jwsString);		     
		   		    
		    
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
	 * Decode jwt token without checking its signature
	 * 
	 * Client can trust the token it got from the server. Servers should always validate them.
	 * 
	 * @param jwsString
	 * @return
	 */
	public static ParsedToken decode(String jwsString) {
		
		// signature must be removed, jjwt refuses to decode signed tokens without validating them 
		String jwtString = jwsToJwt(jwsString);
		
		try {
			@SuppressWarnings("rawtypes")
			Jwt<Header, Claims> jwt = Jwts.parser()
					.parseClaimsJwt(jwtString);

			// now we can safely trust the JWT
			if (jwt.getBody().get(CLAIM_KEY_CLASS) == null || !jwt.getBody().get(CLAIM_KEY_CLASS).toString().equals(CLAIM_VALUE_CLASS)) {
				throw new IllegalStateException("token isn't an " + CLAIM_VALUE_CLASS);
			}

			return claimsToParsedToken(jwt.getBody(), jwsString);		     
		   		    
		    
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

	private static String jwsToJwt(String jws) {
		// https://github.com/jwtk/jjwt/issues/135
		String withoutSignature = jws.substring(0, jws.lastIndexOf(".") + 1);
		return withoutSignature;
	}

	private static ParsedToken claimsToParsedToken(Claims body, String jwsString) {
		Set<String> roles = new HashSet<String>(Arrays.asList(body.get(CLAIM_KEY_ROLES).toString().split(ROLES_DELIMITER)));
	    Instant loginTime = Instant.ofEpochSecond(Long.parseLong(body.get(CLAIM_KEY_LOGIN_TIME).toString()));
	     
	    ParsedToken token = new ParsedToken();
		token.setUsername(body.getSubject()); 
	    token.setTokenKey(jwsString); 
	    token.setValidUntil(body.getExpiration().toInstant()); 
	    token.setCreated(loginTime); 
	    token.setRoles(roles);
	    
	    return token;
	}
}
