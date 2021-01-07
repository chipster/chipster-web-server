package fi.csc.chipster.rest.pretty;

import java.io.IOException;

import org.glassfish.jersey.jackson.internal.jackson.jaxrs.cfg.EndpointConfigBase;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.cfg.ObjectWriterModifier;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectWriter;

import jakarta.ws.rs.core.MultivaluedMap;

public class IndentingModifier extends ObjectWriterModifier {

	@Override
	public ObjectWriter modify(EndpointConfigBase<?> endpoint, MultivaluedMap<String, Object> responseHeaders,
			Object valueToWrite, ObjectWriter w, JsonGenerator g) throws IOException {
		
		g.useDefaultPrettyPrinter();

		return w;
	}
}