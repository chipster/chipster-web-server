package fi.csc.chipster.rest.pretty;

import java.io.IOException;

import org.glassfish.jersey.jackson.internal.jackson.jaxrs.cfg.ObjectWriterInjector;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

@Provider
public class JsonPrettyPrintQueryParamContainerResponseFilter
        implements jakarta.ws.rs.container.ContainerResponseFilter {

    private static final String QUERY_PARAM_PRETTY = "pretty";

    @Override
    public void filter(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext) throws IOException {

        MultivaluedMap<String, String> queryParams = requestContext.getUriInfo().getQueryParameters();

        if (queryParams.containsKey(QUERY_PARAM_PRETTY)) {
            ObjectWriterInjector.set(new IndentingModifier());
        }
    }
}
