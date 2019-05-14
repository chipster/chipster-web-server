package fi.csc.chipster.auth.resource;

import java.util.Optional;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.pac4j.core.profile.CommonProfile;
import org.pac4j.jax.rs.annotations.Pac4JCallback;
import org.pac4j.jax.rs.annotations.Pac4JProfile;

import fi.csc.chipster.rest.Config;

public class OpenIDClientResource {
	
	private Config config;
	
	public OpenIDClientResource(Config config) {
		this.config = config;

	}
	// Testing the pac4j
	
		 @POST
		 @Path("openid")
		 @Produces(MediaType.APPLICATION_JSON)
		 @Pac4JCallback(skipResponse = true)
		    public Response loginTest(@Pac4JProfile Optional<CommonProfile> profile) {
			 System.out.println( "Checking the pac4j");
		        if (profile.isPresent()) {
		            System.out.println(profile.toString());
		        } else {
		            throw new WebApplicationException(401);
		        }
		        
		        return Response.ok().build();
		    }


}
