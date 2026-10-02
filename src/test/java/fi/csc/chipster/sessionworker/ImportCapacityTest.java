package fi.csc.chipster.sessionworker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import fi.csc.chipster.rest.Config;
import jakarta.ws.rs.ServiceUnavailableException;

public class ImportCapacityTest {

	@Test
	public void defaults() throws Exception {
		ImportCapacity capacity = new ImportCapacity(new Config(), new File("."));
		capacity.acquire();
		capacity.release();
	}

	@Test
	public void slots() throws Exception {
		ImportCapacity capacity = new ImportCapacity(2, Duration.ofMillis(10), () -> 0, 0);
		capacity.acquire();
		capacity.acquire();
		assertThrows(ServiceUnavailableException.class, () -> capacity.acquire());

		// released slot can be used again
		capacity.release();
		capacity.acquire();
	}

	@Test
	public void interrupted() throws Exception {
		ImportCapacity capacity = new ImportCapacity(1, Duration.ofSeconds(10), () -> 0, 0);
		capacity.acquire();

		// the next acquire() would wait, interrupt it beforehand
		Thread.currentThread().interrupt();
		try {
			assertThrows(ServiceUnavailableException.class, () -> capacity.acquire());
			// the flag is cleared, so that the caller can still write the response
			assertFalse(Thread.currentThread().isInterrupted());
		} finally {
			// clear the flag in any case, so that it doesn't affect the other tests
			Thread.interrupted();
		}
	}

	@Test
	public void noSlots() {
		assertThrows(IllegalArgumentException.class,
				() -> new ImportCapacity(0, Duration.ZERO, () -> 0, 0));
	}

	@Test
	public void diskSpace() throws Exception {
		ImportCapacity capacity = new ImportCapacity(1, Duration.ZERO, () -> 100, 10);
		capacity.reserveDiskSpace(50);
		capacity.reserveDiskSpace(40);
		// would leave less than 10 bytes free
		assertThrows(ServiceUnavailableException.class, () -> capacity.reserveDiskSpace(1));

		capacity.releaseDiskSpace(40);
		capacity.reserveDiskSpace(40);
	}

	@Test
	public void diskSpaceTooLarge() {
		ImportCapacity capacity = new ImportCapacity(1, Duration.ZERO, () -> 100, 10);
		assertThrows(ServiceUnavailableException.class, () -> capacity.reserveDiskSpace(91));
	}
}
