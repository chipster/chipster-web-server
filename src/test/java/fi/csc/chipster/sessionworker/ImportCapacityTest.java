package fi.csc.chipster.sessionworker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import fi.csc.chipster.rest.Config;
import jakarta.ws.rs.ServiceUnavailableException;

public class ImportCapacityTest {

	private static final Duration SHORT = Duration.ofMillis(10);

	@Test
	public void defaults() throws Exception {
		ImportCapacity capacity = new ImportCapacity(new Config(), new File("."));
		capacity.acquire("a");
		capacity.release("a");
	}

	@Test
	public void slots() throws Exception {
		ImportCapacity capacity = new ImportCapacity(2, 10, 10, SHORT, () -> 0, 0);
		capacity.acquire("a");
		capacity.acquire("b");
		assertThrows(ServiceUnavailableException.class, () -> capacity.acquire("c"));

		// released slot can be used again
		capacity.release("a");
		capacity.acquire("c");
	}

	@Test
	public void userSlots() throws Exception {
		ImportCapacity capacity = new ImportCapacity(10, 2, 10, SHORT, () -> 0, 0);
		capacity.acquire("a");
		capacity.acquire("a");
		// the user has used all of their slots
		assertThrows(ServiceUnavailableException.class, () -> capacity.acquire("a"));
		// but other users still have theirs
		capacity.acquire("b");

		capacity.release("a");
		capacity.acquire("a");
	}

	@Test
	public void userImports() throws Exception {
		ImportCapacity capacity = new ImportCapacity(10, 1, 2, Duration.ofSeconds(10), () -> 0, 0);
		capacity.acquire("a");

		// the second import of the user waits for the first one
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread waiting = new Thread(() -> {
			try {
				capacity.acquire("a");
			} catch (Throwable t) {
				error.set(t);
			}
		});
		waiting.start();
		// wait until it's parked in tryAcquire()
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (waiting.getState() != Thread.State.TIMED_WAITING) {
			assertTrue(System.nanoTime() < deadline, "the second import didn't start waiting");
			Thread.sleep(1);
		}

		// the third is refused right away, without waiting for the timeout
		long start = System.nanoTime();
		assertThrows(ServiceUnavailableException.class, () -> capacity.acquire("a"));
		assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));

		// the waiting one gets the slot after the release
		capacity.release("a");
		waiting.join(5000);
		assertFalse(waiting.isAlive());
		assertNull(error.get(), "the waiting import failed: " + error.get());
		capacity.release("a");
	}

	@Test
	public void userCleanUp() throws Exception {
		ImportCapacity capacity = new ImportCapacity(1, 1, 1, SHORT, () -> 0, 0);
		// a failed wait must not leave the user's import counted
		capacity.acquire("a");
		assertThrows(ServiceUnavailableException.class, () -> capacity.acquire("b"));
		capacity.release("a");

		// both users can import again
		capacity.acquire("b");
		capacity.release("b");
		capacity.acquire("a");
		capacity.release("a");
	}

	@Test
	public void interrupted() throws Exception {
		ImportCapacity capacity = new ImportCapacity(1, 1, 10, Duration.ofSeconds(10), () -> 0, 0);
		capacity.acquire("a");

		// the next acquire() would wait, interrupt it beforehand
		Thread.currentThread().interrupt();
		try {
			assertThrows(ServiceUnavailableException.class, () -> capacity.acquire("b"));
			// the flag is cleared, so that the caller can still write the response
			assertFalse(Thread.currentThread().isInterrupted());
		} finally {
			// clear the flag in any case, so that it doesn't affect the other tests
			Thread.interrupted();
		}
	}

	@Test
	public void releaseWithoutAcquire() {
		ImportCapacity capacity = new ImportCapacity(1, 1, 1, SHORT, () -> 0, 0);
		assertThrows(IllegalStateException.class, () -> capacity.release("a"));
	}

	@Test
	public void noSlots() {
		assertThrows(IllegalArgumentException.class, () -> new ImportCapacity(0, 1, 1, SHORT, () -> 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new ImportCapacity(1, 0, 1, SHORT, () -> 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new ImportCapacity(1, 1, 0, SHORT, () -> 0, 0));
	}

	@Test
	public void diskSpace() throws Exception {
		ImportCapacity capacity = new ImportCapacity(1, 1, 1, Duration.ZERO, () -> 100, 10);
		capacity.reserveDiskSpace(50);
		capacity.reserveDiskSpace(40);
		// would leave less than 10 bytes free
		assertThrows(ServiceUnavailableException.class, () -> capacity.reserveDiskSpace(1));

		capacity.releaseDiskSpace(40);
		capacity.reserveDiskSpace(40);
	}

	@Test
	public void diskSpaceTooLarge() {
		ImportCapacity capacity = new ImportCapacity(1, 1, 1, Duration.ZERO, () -> 100, 10);
		assertThrows(ServiceUnavailableException.class, () -> capacity.reserveDiskSpace(91));
	}
}
