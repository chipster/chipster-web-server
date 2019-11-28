package fi.csc.chipster.toolbox;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.toolbox.sadl.SADLDescription;

/**
 * Created by hupponen on 02/11/2015.
 */
public class ToolboxClientRest {

    public static void main(String args[]) throws IOException {
        Config config = new Config();
        Client client = ClientBuilder.newClient();
//        System.out.println(config.getString("toolbox"));
//        WebTarget webTarget = client.target("http://127.0.0.1:8083/toolbox").path("tools/norm-affy.R/sadl");
        WebTarget webTarget = client.target(config.getString("toolbox")).path("tools/norm-affy.R/sadl");

//        ToolboxTool tool = webTarget.request(MediaType.APPLICATION_JSON).get(ToolboxTool.class);
        String sadl = webTarget.request().get(String.class);
        System.out.println(sadl);
    }
    

	private String baseUri;

	public ToolboxClientRest(String toolboxUri) {
		this.baseUri = toolboxUri;
	}

	public ToolboxTool getTool(String toolId) {
		
		WebTarget serviceTarget = AuthenticationClient.getClient().target(baseUri).path("tools/" + toolId);

		String json = serviceTarget.request(MediaType.APPLICATION_JSON).get(String.class);
		
		ToolboxTool tool = RestUtils.parseJson(ToolboxTool.class, json, false);
		
		return tool;
	}

	public HashMap<String, SADLDescription> getTools() {
		WebTarget serviceTarget = AuthenticationClient.getClient().target(baseUri).path("tools");

		String toolsJson = serviceTarget.request(MediaType.APPLICATION_JSON).get(String.class);
		
		@SuppressWarnings("unchecked")
		List<SADLDescription> tools = RestUtils.parseJson(List.class, SADLDescription.class, toolsJson, false);
		
		HashMap<String, SADLDescription> map = new HashMap<>();
		
		for (SADLDescription tool : tools) {
			map.put(tool.getName().getID(), tool);
		}

		return map;
	}
}
