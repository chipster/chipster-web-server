package fi.csc.chipster.toolbox.resource;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import fi.csc.chipster.toolbox.Toolbox;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.chipster.toolbox.sadl.SADLDescription;

/**
 * Created by hupponen on 12/10/2015.
 */

@Singleton
@Path("tools")
public class ToolResource {

    private Toolbox toolbox;

    public ToolResource(Toolbox toolbox) throws IOException {
        this.toolbox = toolbox;
    }

    @GET
    // @JacksonFeatures(serializationEnable = { SerializationFeature.INDENT_OUTPUT
    // })
    @Produces(MediaType.APPLICATION_JSON)
    public final Response getAll() {
        List<SADLDescription> list = new LinkedList<SADLDescription>();
        for (ToolboxTool tool : toolbox.getAll()) {
            list.add(tool.getSadlDescription());
        }

        return Response.ok(list).build();
    }

    @GET
    @Path("{toolId}")
    @Produces(MediaType.APPLICATION_JSON)
    public final Response getTool(@PathParam("toolId") String toolId) {
        ToolboxTool tool = toolbox.getTool(toolId);
        if (tool != null) {
            return Response.ok(tool).build();
        } else {
            return Response.status(404).build();
        }
    }

    @GET
    @Path("{toolId}/{part}")
    @Produces(MediaType.TEXT_PLAIN)
    public final String getToolPart(@PathParam("toolId") String toolId, @PathParam("part") String part) {
        ToolboxTool tool = toolbox.getTool(toolId);
        if (tool == null) {
            throw new NotFoundException();
        }

        if (part.equals("source")) {
            return tool.getSource();
        } else if (part.equals("sadl")) {
            return tool.getSadlString();
        } else if (part.equals("code")) {
            return tool.getCode();
        } else {
            throw new NotFoundException();
        }
    }

    public void setToolbox(Toolbox newToolbox) {
        this.toolbox = newToolbox;

    }

}
