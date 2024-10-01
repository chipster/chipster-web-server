package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

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

	@BeforeAll
	public static void setUp() throws Exception {
		Config config = new Config();
		launcher = new TestServerLauncher(config);

		user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		noAuthClient = new SessionDbClient(launcher.getServiceLocator(), null, Role.CLIENT);
		noAuthAdminClient = new SessionDbAdminClient(launcher.getServiceLocatorForAdmin(), null);
		adminClient = new SessionDbAdminClient(launcher.getServiceLocatorForAdmin(), launcher.getAdminToken());
	}

	@AfterAll
	public static void tearDown() throws Exception {
		launcher.stop();
	}

	@Test
	public void post() throws IOException, RestException {

		createNews(adminClient);

		testCreateNews(403, noAuthAdminClient);
	}

	public static void testCreateNews(int expected, SessionDbAdminClient client) {
		try {
			createNews(client);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	private static UUID createNews(SessionDbAdminClient client) throws RestException {
		return createNews(client, "{\"testKey\": \"testValue\"}");
	}

	private static UUID createNews(SessionDbAdminClient client, String json) throws RestException {
		News news = new News();

		/*
		 * Setting the contents field looks ugly here in the backend side. Jackson will
		 * embed
		 * this field directly to the parent json structure making it pretty for the
		 * client.
		 */
		news.setContents(RestUtils.parseJson(JsonNode.class, json));

		return client.createNews(news);
	}

	@Test
	public void get() throws RestException {

		UUID newsId = createNews(adminClient);

		user1Client.getNews(newsId);

		// auth tests
		testGetNews(401, newsId, noAuthClient);
	}

	private void testGetNews(int expected, UUID newsId, SessionDbClient client) {
		try {
			client.getNews(newsId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	@Test
	public void getAll() throws RestException {

		UUID testValue = RestUtils.createUUID();

		createNews(adminClient, "{\"testKey\": \"" + testValue.toString() + "\"}");

		// check that the testValue is found from the list of all news
		RestUtils.asJson(user1Client.getNews()).contains(testValue.toString());

		// auth tests
		testGetAllNews(401, noAuthClient);
	}

	private void testGetAllNews(int expected, SessionDbClient client) {
		try {
			client.getNews();
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	@Test
	public void put() throws RestException {

		UUID newsId = createNews(adminClient);

		News news = user1Client.getNews(newsId);

		news.setContents(RestUtils.parseJson(JsonNode.class, "{\"testKey\": \"testValue2\"}"));

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

		UUID newsId = createNews(adminClient);

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
