package fi.csc.chipster.toolbox.resource;

import java.io.IOException;

import fi.csc.chipster.toolbox.Toolbox;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Singleton
@Path(RuntimeResource.PATH_RUNTIMES)
public class RuntimeResource {

    public static final String PATH_RUNTIMES = "runtimes";
	private Toolbox toolbox;

    public RuntimeResource(Toolbox toolbox) throws IOException {
        this.toolbox = toolbox;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public final Response getRuntimes() throws IOException {
    	
    	return Response.ok(this.toolbox.getRuntimes()).build();
    }

	public void setToolbox(Toolbox newToolbox) {
		this.toolbox = newToolbox;
	}

}

