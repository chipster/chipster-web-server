package fi.csc.chipster.rest.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;

/**
 * Test the error handling without a server
 * 
 * The headers and the buffered content of a file that the servlet couldn't
 * finish are easier to set up here than through a real servlet.
 */
public class ExceptionServletFilterTest {

    /**
     * Response stub that records the status, the headers and the content
     * 
     * The methods that it doesn't implement return the default value of their
     * type, so that the filter can call them freely.
     */
    private static class ResponseStub {
        boolean committed;
        Integer status;
        String contentType;
        Map<String, String> headers = new HashMap<>();
        ByteArrayOutputStream content = new ByteArrayOutputStream();

        HttpServletResponse proxy() {
            ServletOutputStream out = new ServletOutputStream() {
                @Override
                public void write(int b) {
                    content.write(b);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                }
            };

            return (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { HttpServletResponse.class }, (p, method, args) -> {
                        switch (method.getName()) {
                        case "isCommitted":
                            return committed;
                        case "setStatus":
                            status = (Integer) args[0];
                            return null;
                        case "setContentType":
                            contentType = (String) args[0];
                            return null;
                        case "setHeader":
                            if (args[1] == null) {
                                headers.remove(args[0]);
                            } else {
                                headers.put((String) args[0], (String) args[1]);
                            }
                            return null;
                        case "resetBuffer":
                            content.reset();
                            return null;
                        case "getOutputStream":
                            return out;
                        default:
                            return defaultValue(method.getReturnType());
                        }
                    });
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        } else if (type == int.class) {
            return 0;
        } else if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private static HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(ExceptionServletFilterTest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class }, (p, method, args) -> defaultValue(method.getReturnType()));
    }

    private static void filter(ResponseStub response, FilterChain chain) throws Exception {
        new ExceptionServletFilter().doFilter(request(), response.proxy(), chain);
    }

    @Test
    public void sendMappedError() throws Exception {
        ResponseStub response = new ResponseStub();

        filter(response, (req, resp) -> {
            // headers and content of a file that the servlet couldn't finish
            ((HttpServletResponse) resp).setHeader("Content-Disposition", "attachment; filename=\"x.html\"");
            ((HttpServletResponse) resp).setContentType(MediaType.TEXT_HTML);
            resp.getOutputStream().write("<html>".getBytes());
            throw new NotFoundException("dataset not found");
        });

        assertEquals(404, response.status);
        assertEquals("dataset not found", response.content.toString());
        assertNull(response.headers.get("Content-Disposition"));
        assertEquals(MediaType.TEXT_PLAIN, response.contentType);
    }

    @Test
    public void sendGenericError() throws Exception {
        ResponseStub response = new ResponseStub();

        filter(response, (req, resp) -> {
            throw new IllegalStateException("internal details");
        });

        // the message of the exception isn't shown to the client
        assertEquals(500, response.status);
        assertEquals("servlet error", response.content.toString());
        assertEquals(MediaType.TEXT_PLAIN, response.contentType);
    }

    @Test
    public void abortCommittedResponseGeneric() throws Exception {
        // e.g. storage failed in the middle of a download
        ResponseStub response = new ResponseStub();
        response.committed = true;

        IOException thrown = new IOException("storage failed in the middle of the file");

        IOException e = assertThrows(IOException.class, () -> filter(response, (req, resp) -> {
            throw thrown;
        }));

        // rethrown for Jetty to abort the response, so that the client doesn't take a
        // truncated file for a success
        assertSame(thrown, e);
        assertNull(response.status);
        assertEquals(0, response.content.size());
    }
}
