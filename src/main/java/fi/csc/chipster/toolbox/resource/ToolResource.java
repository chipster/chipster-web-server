package fi.csc.chipster.toolbox.resource;

import fi.csc.chipster.toolbox.Toolbox;
import fi.csc.chipster.toolbox.ToolboxModule;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.microarray.messaging.message.ModuleDescriptionMessage;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;

/**
 * Created by hupponen on 12/10/2015.
 */

@Singleton
@Path("tools")
public class ToolResource {

    private Toolbox toolbox;

    public ToolResource() throws IOException {
        toolbox = new Toolbox(new File("../chipster/src/main/modules"));
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public final String get() {
        String list = "";
        for (ToolboxTool tool : toolbox.getAll()) {
            list += tool.getResourceName()  + "\n";
        }
        return list;
    }


    @GET @Path("{toolId}")
    @Produces(MediaType.TEXT_PLAIN)
    public final Response getTool(@PathParam("toolId") String toolId) {
        ToolboxTool tool = toolbox.getTool(toolId);
        if (tool != null) {
            return Response.ok(tool.getSource()).build();
        } else {
            return Response.status(404).build();
        }
    }


    @GET @Path("{toolId}/{part}")
    @Produces(MediaType.TEXT_PLAIN)
    public final String getToolPart(@PathParam("toolId") String toolId, @PathParam("part") String part) {
        ToolboxTool tool = toolbox.getTool(toolId);
        if (tool == null) {
            throw new WebApplicationException(404);
        }

        if (part == null || part.isEmpty()) {
            return tool.getSource();
        } else if (part.equals("sadl")) {
            return tool.getSADL();
        } else if (part.equals("code")) {
            return tool.getCode();
        } else {
            throw new WebApplicationException(404);
        }
    }
}


