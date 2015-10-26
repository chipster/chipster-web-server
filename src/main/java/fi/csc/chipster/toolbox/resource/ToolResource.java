package fi.csc.chipster.toolbox.resource;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

/**
 * Created by hupponen on 12/10/2015.
 */

@Path("tools" )
public class ToolResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String get() {
        return "tool test";
    }

}
