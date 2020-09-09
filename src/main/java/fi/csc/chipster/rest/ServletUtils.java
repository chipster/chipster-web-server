package fi.csc.chipster.rest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;

import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.token.BasicAuthParser;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.sessiondb.RestException;

public class ServletUtils {
	
	public static String getToken(HttpServletRequest request) {
    	
		String tokenParameter = request.getParameter(TokenRequestFilter.QUERY_PARAMETER_TOKEN);
		String tokenHeader = request.getHeader(TokenRequestFilter.HEADER_AUTHORIZATION);

		return getToken(tokenHeader, tokenParameter);
	}
	
	public static WebApplicationException extractRestException(RestException e) {
		int statusCode = e.getResponse().getStatus();
		String msg = e.getMessage();
		if (statusCode == HttpServletResponse.SC_FORBIDDEN) {
			return new ForbiddenException(msg);
		} else if (statusCode == HttpServletResponse.SC_UNAUTHORIZED) {
			return new NotAuthorizedException(msg);
		} else if (statusCode == HttpServletResponse.SC_NOT_FOUND) {
			return new NotFoundException(msg);
		} else {
			return new InternalServerErrorException(e);
		}
	}
	
	public static String getToken(String authHeader, String authParameter) {
		if (authHeader != null) {
			BasicAuthParser parser = new BasicAuthParser(authHeader);
			if (!TokenRequestFilter.TOKEN_USER.equals(parser.getUsername())) {
				throw new NotAuthorizedException("only tokens allowed");
			}
			return parser.getPassword();
		} else {
			return authParameter;
		}
	}
}
