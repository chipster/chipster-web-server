package fi.csc.chipster.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.scheduler.resource.SchedulerResource;

public class SchedulerResourceTest {

	private static TestServerLauncher launcher;

	@BeforeAll
	public static void setUp() throws Exception {
		Config config = new Config();
		launcher = new TestServerLauncher(config);
	}

	@AfterAll
	public static void tearDown() throws Exception {
		launcher.stop();
	}

	@Test
	public void getQuotas() throws IOException {

		SchedulerClient client = new SchedulerClient(launcher.getTargetUri(Role.SCHEDULER));

		assertEquals(200, client.getQuotas().get(SchedulerResource.KEY_DEFAULT_STORAGE));
	}
}
