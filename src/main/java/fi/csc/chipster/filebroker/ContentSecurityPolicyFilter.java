package fi.csc.chipster.filebroker;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Sandbox every response of the file-broker
 *
 * The files are user content, but the file-broker is served from the same
 * origin as the app. Without a sandbox, a html file opened in the browser (e.g.
 * "Open in new tab") could run scripts on the origin of the app and read the
 * auth token of the user from the localStorage.
 *
 * The sandbox gives the file an opaque origin. Scripts still run (needed for
 * interactive reports), but they can't access the storage, cookies or pages of
 * the app. The new tabs which a file opens may escape the sandbox, so that
 * links to other sites work normally. That doesn't give the file a way out,
 * because every response of the file-broker is sandboxed, also when the file
 * opens its own URL again.
 *
 * The same flags as in the iframe of the html visualization of the app. Unlike the
 * iframe, a file opened in a new tab gets no stand-ins for the localStorage and
 * cookies which the sandbox denies: a report which keeps its settings in them
 * (e.g. MultiQC) works, but logs errors and doesn't keep the settings.
 *
 * This is a filter rather than a part of the servlet, so that the header is set
 * before anything is decided about the request: also error messages, responses
 * to uploads and responses to unsupported methods get it. The servlet removes it
 * from the responses which the browsers can't show in a sandbox.
 */
public class ContentSecurityPolicyFilter implements Filter {

    public static final String HEADER_CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    public static final String CONTENT_SECURITY_POLICY = "sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox allow-downloads allow-modals allow-forms";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        ((HttpServletResponse) response).setHeader(HEADER_CONTENT_SECURITY_POLICY, CONTENT_SECURITY_POLICY);

        chain.doFilter(request, response);
    }
}
