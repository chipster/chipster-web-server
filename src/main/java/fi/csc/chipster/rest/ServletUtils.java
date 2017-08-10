package fi.csc.chipster.rest;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.ForbiddenException;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.rest.token.TokenRequestFilter;

public class ServletUtils {

	public static boolean isInRole(String role, HttpServletRequest request, AuthenticationClient authService) {
		String authHeader = request.getHeader(TokenRequestFilter.HEADER_AUTHORIZATION);
		if (authHeader == null) {
			throw new ForbiddenException("no Authorization header");
		}
		String token = TokenRequestFilter.getToken(authHeader, null);
		if (token == null) {
			throw new ForbiddenException("no token found from the header");
		}
		Token dbToken = authService.getDbToken(token);
		if (dbToken == null) {
			throw new ForbiddenException("invalid token");
		}
		return dbToken.getRoles().contains(role);
	}
}
