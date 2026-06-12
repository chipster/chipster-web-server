package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Label;

public class SessionLabelResourceTest {

	private static TestServerLauncher launcher;

	private static SessionDbClient user1Client;
	private static SessionDbClient user2Client;
	private static SessionDbClient noAuthClient;
	private static UUID sessionId1;
	private static UUID sessionId2;

	// sessions created by user1, deleted in tearDown even if a test fails
	private static List<UUID> user1SessionIds = new ArrayList<>();

	@BeforeAll
	public static void setUp() throws Exception {
		Config config = new Config();
		launcher = new TestServerLauncher(config);

		user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		user2Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);
		noAuthClient = new SessionDbClient(launcher.getServiceLocator(), null, Role.CLIENT);

		sessionId1 = createUser1Session();
		sessionId2 = user2Client.createSession(RestUtils.getRandomSession());
	}

	@AfterAll
	public static void tearDown() throws Exception {
		try {
			for (UUID sessionId : user1SessionIds) {
				user1Client.deleteSession(sessionId);
			}
			user2Client.deleteSession(sessionId2);
		} finally {
			launcher.stop();
		}
	}

	private static UUID createUser1Session() throws RestException {
		UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
		user1SessionIds.add(sessionId);
		return sessionId;
	}

	@Test
	public void post() throws RestException {
		UUID id = user1Client.createLabel(sessionId1, RestUtils.getRandomLabel());
		assertNotNull(id);
	}

	@Test
	public void postWithId() throws RestException {
		Label label = RestUtils.getRandomLabel();
		label.setLabelIdPair(sessionId1, RestUtils.createUUID());
		user1Client.createLabel(sessionId1, label);
	}

	@Test
	public void postWithWrongId() throws RestException {
		Label label = RestUtils.getRandomLabel();
		label.setLabelIdPair(sessionId2, RestUtils.createUUID());
		testCreateLabel(400, sessionId1, label, user1Client);
	}

	@Test
	public void postEmptyName() throws RestException {
		Label label = RestUtils.getRandomLabel();
		label.setName("");
		testCreateLabel(400, sessionId1, label, user1Client);

		Label whitespace = RestUtils.getRandomLabel();
		whitespace.setName("   ");
		testCreateLabel(400, sessionId1, whitespace, user1Client);

		Label nullName = RestUtils.getRandomLabel();
		nullName.setName(null);
		testCreateLabel(400, sessionId1, nullName, user1Client);
	}

	@Test
	public void postTooLongName() throws RestException {
		Label label = RestUtils.getRandomLabel();
		// MAX_NAME_LENGTH is 30; build a 31-char name
		label.setName("x".repeat(Label.MAX_NAME_LENGTH + 1));
		testCreateLabel(400, sessionId1, label, user1Client);
	}

	@Test
	public void postMaxLengthName() throws RestException {
		// exactly 30 chars must be accepted
		Label label = RestUtils.getRandomLabel();
		label.setName("x".repeat(Label.MAX_NAME_LENGTH));
		user1Client.createLabel(sessionId1, label);
	}

	@Test
	public void postTooLongColor() throws RestException {
		Label label = RestUtils.getRandomLabel();
		label.setColor("z".repeat(Label.MAX_COLOR_LENGTH + 1));
		testCreateLabel(400, sessionId1, label, user1Client);
	}

	@Test
	public void postMaxLengthColor() throws RestException {
		Label label = RestUtils.getRandomLabel();
		label.setColor("z".repeat(Label.MAX_COLOR_LENGTH));
		user1Client.createLabel(sessionId1, label);
	}

	@Test
	public void postNullColor() throws RestException {
		// color is optional
		Label label = RestUtils.getRandomLabel();
		label.setColor(null);
		user1Client.createLabel(sessionId1, label);
	}

	@Test
	public void putTooLongColor() throws RestException {
		Label label = RestUtils.getRandomLabel();
		user1Client.createLabel(sessionId1, label);

		label.setColor("z".repeat(Label.MAX_COLOR_LENGTH + 1));
		testUpdateLabel(400, sessionId1, label, user1Client);
	}

	@Test
	public void postAtMaxLabelsPerSession() throws RestException {
		// fresh session so existing tests don't influence the count
		UUID sessionId = createUser1Session();
		for (int i = 0; i < Label.MAX_LABELS_PER_SESSION; i++) {
			user1Client.createLabel(sessionId, RestUtils.getRandomLabel());
		}
		assertEquals(Label.MAX_LABELS_PER_SESSION, user1Client.getLabels(sessionId).size());
	}

	@Test
	public void postOverMaxLabelsPerSession() throws RestException {
		UUID sessionId = createUser1Session();
		for (int i = 0; i < Label.MAX_LABELS_PER_SESSION; i++) {
			user1Client.createLabel(sessionId, RestUtils.getRandomLabel());
		}
		testCreateLabel(400, sessionId, RestUtils.getRandomLabel(), user1Client);
	}

	@Test
	public void postAfterDeleteUnderMax() throws RestException {
		UUID sessionId = createUser1Session();
		UUID firstLabelId = null;
		for (int i = 0; i < Label.MAX_LABELS_PER_SESSION; i++) {
			UUID id = user1Client.createLabel(sessionId, RestUtils.getRandomLabel());
			if (i == 0) {
				firstLabelId = id;
			}
		}
		// at cap: one more must fail
		testCreateLabel(400, sessionId, RestUtils.getRandomLabel(), user1Client);
		// drain by one, next must succeed
		user1Client.deleteLabel(sessionId, firstLabelId);
		user1Client.createLabel(sessionId, RestUtils.getRandomLabel());
	}

	@Test
	public void postWrongUser() throws RestException {
		testCreateLabel(403, sessionId1, RestUtils.getRandomLabel(), user2Client);
	}

	@Test
	public void get() throws RestException {
		Label label = RestUtils.getRandomLabel();
		UUID id = user1Client.createLabel(sessionId1, label);

		Label fetched = user1Client.getLabel(sessionId1, id);
		assertNotNull(fetched);
		assertEquals(label.getName(), fetched.getName());
		assertEquals(label.getColor(), fetched.getColor());
		assertEquals(sessionId1, fetched.getSessionId());
		assertEquals(id, fetched.getLabelId());

		// wrong user
		testGetLabel(403, sessionId1, id, user2Client);

		// wrong session
		testGetLabel(403, sessionId2, id, user1Client);
		testGetLabel(404, sessionId2, id, user2Client);
	}

	@Test
	public void getAll() throws RestException {
		UUID id1 = user1Client.createLabel(sessionId1, RestUtils.getRandomLabel());
		UUID id2 = user1Client.createLabel(sessionId1, RestUtils.getRandomLabel());

		assertTrue(user1Client.getLabels(sessionId1).containsKey(id1));
		assertTrue(user1Client.getLabels(sessionId1).containsKey(id2));

		// wrong user
		testGetLabels(403, sessionId1, user2Client);

		// other session doesn't see them
		assertEquals(false, user2Client.getLabels(sessionId2).containsKey(id1));
	}

	@Test
	public void put() throws RestException {
		Label label = RestUtils.getRandomLabel();
		UUID id = user1Client.createLabel(sessionId1, label);

		label.setName("renamed");
		label.setColor("success");
		user1Client.updateLabel(sessionId1, label);

		Label fetched = user1Client.getLabel(sessionId1, id);
		assertEquals("renamed", fetched.getName());
		assertEquals("success", fetched.getColor());

		// wrong user
		testUpdateLabel(403, sessionId1, label, user2Client);

		// wrong session
		testUpdateLabel(403, sessionId2, label, user1Client);
		testUpdateLabel(404, sessionId2, label, user2Client);
	}

	@Test
	public void putEmptyName() throws RestException {
		Label label = RestUtils.getRandomLabel();
		user1Client.createLabel(sessionId1, label);

		label.setName("");
		testUpdateLabel(400, sessionId1, label, user1Client);

		label.setName("   ");
		testUpdateLabel(400, sessionId1, label, user1Client);

		label.setName(null);
		testUpdateLabel(400, sessionId1, label, user1Client);
	}

	@Test
	public void putTooLongName() throws RestException {
		Label label = RestUtils.getRandomLabel();
		user1Client.createLabel(sessionId1, label);

		label.setName("y".repeat(Label.MAX_NAME_LENGTH + 1));
		testUpdateLabel(400, sessionId1, label, user1Client);
	}

	@Test
	public void delete() throws RestException {
		UUID id = user1Client.createLabel(sessionId1, RestUtils.getRandomLabel());

		// wrong user
		testDeleteLabel(403, sessionId1, id, user2Client);

		// wrong session
		testDeleteLabel(403, sessionId2, id, user1Client);
		testDeleteLabel(404, sessionId2, id, user2Client);

		// delete
		user1Client.deleteLabel(sessionId1, id);

		// gone
		testGetLabel(404, sessionId1, id, user1Client);
	}

	@Test
	public void deleteScrubsLabelIdFromDatasets() throws RestException {
		// create a label and a dataset that references it
		UUID labelId = user1Client.createLabel(sessionId1, RestUtils.getRandomLabel());
		Dataset dataset = RestUtils.getRandomDataset();
		dataset.setLabelIds(Arrays.asList(labelId));
		UUID datasetId = user1Client.createDataset(sessionId1, dataset);

		// sanity check: the dataset has the labelId
		Dataset fetched = user1Client.getDataset(sessionId1, datasetId);
		assertTrue(fetched.getLabelIds().contains(labelId));

		// delete the label
		user1Client.deleteLabel(sessionId1, labelId);

		// the labelId should be scrubbed from the dataset
		Dataset afterDelete = user1Client.getDataset(sessionId1, datasetId);
		List<UUID> remaining = afterDelete.getLabelIds();
		assertTrue(remaining == null || !remaining.contains(labelId));
	}

	@Test
	public void getMissing() throws RestException {
		testGetLabel(404, sessionId1, RestUtils.createUUID(), user1Client);
	}

	@Test
	public void unauthenticated() throws RestException {
		// no credentials must be rejected on every label endpoint
		testGetLabels(401, sessionId1, noAuthClient);
		testGetLabel(401, sessionId1, RestUtils.createUUID(), noAuthClient);
		testCreateLabel(401, sessionId1, RestUtils.getRandomLabel(), noAuthClient);
	}

	// helpers

	public static void testCreateLabel(int expected, UUID sessionId, Label label, SessionDbClient client) {
		try {
			client.createLabel(sessionId, label);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	public static void testGetLabel(int expected, UUID sessionId, UUID labelId, SessionDbClient client) {
		try {
			client.getLabel(sessionId, labelId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	public static void testGetLabels(int expected, UUID sessionId, SessionDbClient client) {
		try {
			client.getLabels(sessionId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	public static void testUpdateLabel(int expected, UUID sessionId, Label label, SessionDbClient client) {
		try {
			client.updateLabel(sessionId, label);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	public static void testDeleteLabel(int expected, UUID sessionId, UUID labelId, SessionDbClient client) {
		try {
			client.deleteLabel(sessionId, labelId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}
}
