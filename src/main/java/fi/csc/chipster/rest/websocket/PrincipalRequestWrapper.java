package fi.csc.chipster.rest.websocket;
import java.security.Principal;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import fi.csc.chipster.auth.resource.AuthPrincipal;

public class PrincipalRequestWrapper extends HttpServletRequestWrapper {

	HttpServletRequest realRequest;
	private AuthPrincipal principal;
	private String servletPath;

	public PrincipalRequestWrapper(AuthPrincipal principal, HttpServletRequest request) {
		super(request);
		this.principal = principal;
		this.realRequest = request;
	}

	@Override
	public boolean isUserInRole(String role) {
		return principal.getRoles().contains(role);
	}

	@Override
	public Principal getUserPrincipal() {
		return principal;
	}
    
    @Override
    public String getPathInfo() {
    	String pathInfo = super.getPathInfo();
    	return pathInfo;
    }
    
    @Override
    public String getServletPath() {
    	if (this.servletPath != null) {
    		return this.servletPath;
    	}
        return super.getServletPath();        
    }

	public void setServletPath(String servletPath) {
		this.servletPath = servletPath;
	}
}