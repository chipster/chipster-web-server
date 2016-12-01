package fi.csc.chipster.rest.token;

import java.io.IOException;
import java.security.Principal;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.resource.AuthPrincipal;

/**
 * Most of the authenticated services are implemented with JAX-RS and authenticated by
 * TokenRequestFilter. This is a servlet filter mimicing the same functionality to protect services that
 * are implemented as servlets.
 * 
 * @author klemela
 *
 */
public class TokenServletFilter implements Filter {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();
	private TokenRequestFilter tokenRequestFilter;

	public TokenServletFilter(TokenRequestFilter tokenRequestFilter) {
		this.tokenRequestFilter = tokenRequestFilter;
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
			throws IOException, ServletException {	
		
		HttpServletRequest request = (HttpServletRequest)req;
		HttpServletResponse response = (HttpServletResponse)resp;
		
		String tokenParameter = request.getParameter(TokenRequestFilter.QUERY_PARAMETER_TOKEN);
		String tokenHeader = request.getHeader(TokenRequestFilter.HEADER_AUTHORIZATION);
		
		if ("OPTIONS".equals(request.getMethod())) {			
			// CORS preflight checks require unauthenticated OPTIONS
			chain.doFilter(request, response);
			return;
		}
	
		String token = tokenRequestFilter.getToken(tokenHeader, tokenParameter);
		AuthPrincipal principal = tokenRequestFilter.tokenAuthentication(token);
		
		chain.doFilter(new AuthenticatedRequest(request, principal), response);
	}

	@Override
	public void destroy() {
	}
	
	public static class AuthenticatedRequest extends HttpServletRequestWrapper {

		private AuthPrincipal principal;

		public AuthenticatedRequest(HttpServletRequest request, AuthPrincipal principal) {
			super(request);
			this.principal = principal;
		}
		
		@Override
		public Principal getUserPrincipal() {
			return principal;
		}
	}
}
