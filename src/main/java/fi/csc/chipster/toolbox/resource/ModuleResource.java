package fi.csc.chipster.toolbox.resource;

import java.io.IOException;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.jaxrs.annotation.JacksonFeatures;

import fi.csc.chipster.toolbox.Toolbox;
import fi.csc.chipster.toolbox.ToolboxModule;
import fi.csc.chipster.toolbox.ToolboxModule.ToolboxCategory;
import fi.csc.chipster.toolbox.ToolboxTool;

@Singleton
@Path("modules")
public class ModuleResource {

    private Toolbox toolbox;

    public ModuleResource(Toolbox toolbox) throws IOException {
        this.toolbox = toolbox;
    }

    @GET
//    @JacksonFeatures(serializationEnable =  { SerializationFeature.INDENT_OUTPUT })
    @Produces(MediaType.APPLICATION_JSON)
    public final Response getModules() throws IOException {
    	
        // node factory for creating json nodes
        JsonNodeFactory factory = new JsonNodeFactory(false);
 
        ArrayNode modules = factory.arrayNode();

        // modules
        for (ToolboxModule toolboxModule: toolbox.getModules()) {
        	ObjectNode module = factory.objectNode();
        	module.put("name", toolboxModule.getName());
        	
        	// categories in a module 
        	ArrayNode categories = factory.arrayNode();
        	module.set("categories", categories);
        	for (ToolboxCategory toolboxCategory : toolboxModule.getCategories()) {
    			ObjectNode category = factory.objectNode();
    			category.put("name", toolboxCategory.getName());
    			category.put("color", toolboxCategory.getColor());
    			category.put("hidden", toolboxCategory.isHidden());
    			
				// tools in a category
				ArrayNode tools = factory.arrayNode();
				category.set("tools", tools);
    			for (ToolboxTool toolboxTool : toolboxCategory.getTools()) {
    				tools.add(toolboxTool.getId());
    			}
    			categories.add(category);
    		}
        	
        	modules.add(module);
        }
        
        return Response.ok(modules).build();
    }
}

