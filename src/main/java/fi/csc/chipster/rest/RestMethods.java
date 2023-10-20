package fi.csc.chipster.rest;

import java.util.List;
import java.util.UUID;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import fi.csc.chipster.sessiondb.RestException;

/**
 * Methods for handling JSON objects to and from a REST API
 * 
 * Throws RestException on HTTP errors.
 * 
 * @author klemela
 *
 */
public class RestMethods {
	
	@SuppressWarnings("unchecked")
	public static <T> List<T> getList(WebTarget target, Class<T> type) throws RestException {
		Response response = target.request().get(Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("get a list of " + type.getSimpleName() + " failed ", response, target.getUri());
		}
		String json = response.readEntity(String.class);
		return RestUtils.parseJson(List.class, type, json);
	}

	public static String getJson(WebTarget target) throws RestException {
		Response response = target.request().get(Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("get json failed ", response, target.getUri());
		}
		String json = response.readEntity(String.class);
		return json;
	}

	
	
	
	public static <T> T get(WebTarget target, Class<T> type) throws RestException {
		Response response = target.request().get(Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("get " + type.getSimpleName() + " failed ", response, target.getUri());
		}		
		return response.readEntity(type);
	}
	
	public static UUID post(WebTarget target, Object obj) throws RestException {
		Response response = target.request().post(Entity.entity(obj, MediaType.APPLICATION_JSON), Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("post " + obj.getClass().getSimpleName() + " failed ", response, target.getUri());
		}
		return UUID.fromString(RestUtils.basename(response.getLocation().getPath()));		
	}
	
	public static <T> T postWithObjectResponse(WebTarget target, Object obj, Class<T> responseType) throws RestException {
		Entity<Object> entity = null;
		if (obj != null) {
			entity = Entity.entity(obj, MediaType.APPLICATION_JSON);
		} else {
			entity = Entity.json("");
		}
		Response response = target.request().post(entity, Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("post " + (obj == null ? null : obj.getClass().getSimpleName()) + " failed ", response, target.getUri());
		}
		
		return response.readEntity(responseType);		
	}
		
	public static Response put(WebTarget target, Object obj) throws RestException {
		Response response = target.request().put(Entity.entity(obj, MediaType.APPLICATION_JSON), Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("put " + obj.getClass().getSimpleName() + " failed ", response, target.getUri());
		}
		return response;
	}
	
	public static void delete(WebTarget target) throws RestException {
		Response response = target.request().delete(Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("delete failed ", response, target.getUri());
		}
	}
}
