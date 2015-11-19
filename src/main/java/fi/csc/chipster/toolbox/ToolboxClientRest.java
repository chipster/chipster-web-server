package fi.csc.chipster.toolbox;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import fi.csc.chipster.rest.Config;

/**
 * Created by hupponen on 02/11/2015.
 */
public class ToolboxClientRest {

    public static void main(String args[]) {
        Config config = new Config();
        Client client = ClientBuilder.newClient();
//        System.out.println(config.getString("toolbox"));
//        WebTarget webTarget = client.target("http://127.0.0.1:8083/toolbox").path("tools/norm-affy.R/sadl");
        WebTarget webTarget = client.target(config.getString("toolbox")).path("tools/norm-affy.R/sadl");

//        ToolboxTool tool = webTarget.request(MediaType.APPLICATION_JSON).get(ToolboxTool.class);
        String sadl = webTarget.request().get(String.class);
        System.out.println(sadl);
    }

}
