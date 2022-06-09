package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.News;

public class NewsResourceTest {

	private static TestServerLauncher launcher;
	private static SessionDbClient user1Client;
	private static SessionDbClient noAuthClient;
	private static SessionDbAdminClient adminClient;
	private static SessionDbAdminClient noAuthAdminClient;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
        	
        
		user1Client 		= new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		noAuthClient 		= new SessionDbClient(launcher.getServiceLocator(), null, Role.CLIENT);
		noAuthAdminClient 	= new SessionDbAdminClient(launcher.getServiceLocatorForAdmin(), null);
		adminClient 		= new SessionDbAdminClient(launcher.getServiceLocatorForAdmin(), launcher.getAdminToken());
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }

    @Test
    public void post() throws IOException, RestException {
    	
    	News news = new News();
    	news.setContents("{\"testKey\": \"testValue\"}");
    	
    	adminClient.createNews(news);
    	
    	testCreateNews(403, noAuthAdminClient, news);
    }

	public static void testCreateNews(int expected, SessionDbAdminClient client, News news) {
		try {
    		client.createNews(news);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}

	@Test
    public void get() throws RestException {
		
		UUID testValue = RestUtils.createUUID();
		
		News news = new News();
    	news.setContents("{\"testKey\": \"" + testValue.toString() + "\"}");
    	
    	adminClient.createNews(news);
    	
    	List<News> filteredNews = user1Client.getNews().stream()
    		.filter(n -> {
    			HashMap<String, Object> jsonMap;
				try {
					if (n.getContents() == null) {
						return false;
					}
					jsonMap = RestUtils.parseJsonToMap(n.getContents());
					return jsonMap.containsKey("testKey") && testValue.toString().equals(jsonMap.get("testKey"));
					
				} catch (JsonProcessingException e) {
					e.printStackTrace();
					assertEquals(true, false);
					return false;
				}
    		})
    		.collect(Collectors.toList());
    	
    	assertEquals(1, filteredNews.size());
						
		// auth tests
		testGetNews(401, noAuthClient);
    }
				
	private void testGetNews(int expected, SessionDbClient client) {
		try {
    		client.getSessions();
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
		
	@Test
    public void put() throws RestException {
		
		News news = new News();
    	news.setContents("{\"testKey\": \"testValue\"}");
    	
    	adminClient.createNews(news);
    	
    	news.setContents("{\"testKey\": \"testValue2\"}");
    	
    	adminClient.updateNews(news);
    		
		testUpdateNews(403, noAuthAdminClient, news);
    }
	
	
	public static void testUpdateNews(int expected, SessionDbAdminClient client, News news) {
		try {
    		client.updateNews(news);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	@Test
    public void delete() throws RestException {
		
		News news = new News();
    	news.setContents("{\"testKey\": \"testValue\"}");
    	
    	UUID newsId = adminClient.createNews(news);
    	
    	testDeleteNews(403, newsId, noAuthAdminClient);
		
		// delete
		adminClient.deleteNews(newsId);
    }
	
	public static void testDeleteNews(int expected, UUID newsId, SessionDbAdminClient client) {
		try {
    		client.deleteNews(newsId);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
}
