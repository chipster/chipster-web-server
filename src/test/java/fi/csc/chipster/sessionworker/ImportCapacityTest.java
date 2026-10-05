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
        ImportCapacity capacity = new ImportCapacity(2, 100, 10, 10, SHORT, () -> 0, 0);
        capacity.acquire("a");
        capacity.acquire("b");
        assertThrows(ServiceUnavailableException.class, () -> capacity.acquire("c"));

        // released slot can be used again
        capacity.release("a");
        capacity.acquire("c");
    }

    @Test
    public void userSlots() throws Exception {
        ImportCapacity capacity = new ImportCapacity(10, 100, 2, 10, SHORT, () -> 0, 0);
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
        ImportCapacity capacity = new ImportCapacity(10, 100, 1, 2, Duration.ofSeconds(10), () -> 0, 0);
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
        ImportCapacity capacity = new ImportCapacity(1, 100, 1, 1, SHORT, () -> 0, 0);
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
        ImportCapacity capacity = new ImportCapacity(1, 100, 1, 10, Duration.ofSeconds(10), () -> 0, 0);
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
        ImportCapacity capacity = new ImportCapacity(1, 100, 1, 1, SHORT, () -> 0, 0);
        assertThrows(IllegalStateException.class, () -> capacity.release("a"));
    }

    @Test
    public void noSlots() {
        assertThrows(IllegalArgumentException.class, () -> new ImportCapacity(0, 100, 1, 1, SHORT, () -> 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ImportCapacity(1, 0, 1, 1, SHORT, () -> 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ImportCapacity(1, 100, 0, 1, SHORT, () -> 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ImportCapacity(1, 100, 1, 0, SHORT, () -> 0, 0));
    }

    @Test
    public void maxImports() throws Exception {
        // one import can run, two can be waiting or running in total
        ImportCapacity capacity = new ImportCapacity(1, 2, 10, 10, Duration.ofSeconds(10), () -> 0, 0);
        capacity.acquire("a");

        Thread waiting = startWaiting(capacity, "b", new ImportCapacity.Waiter(), new AtomicReference<>());

        // the third is refused right away, although it's from a new user
        long start = System.nanoTime();
        assertThrows(ServiceUnavailableException.class, () -> capacity.acquire("c"));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));

        // the refused one isn't counted, so there is room again after the first one
        // has finished and the waiting one has started
        capacity.release("a");
        waiting.join(5000);
        assertFalse(waiting.isAlive());
        capacity.release("b");
        capacity.acquire("c");
    }

    @Test
    public void cancel() throws Exception {
        ImportCapacity capacity = new ImportCapacity(1, 10, 10, 10, Duration.ofSeconds(10), () -> 0, 0);
        capacity.acquire("a");

        ImportCapacity.Waiter waiter = new ImportCapacity.Waiter();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread waiting = startWaiting(capacity, "b", waiter, error);

        // the cancelled wait ends right away, without waiting for the timeout
        long start = System.nanoTime();
        waiter.cancel();
        waiting.join(5000);
        assertFalse(waiting.isAlive());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        assertTrue(error.get() instanceof ServiceUnavailableException, "unexpected error: " + error.get());
        assertTrue(waiter.isCancelled());

        // the cancelled import isn't counted and didn't take the slot
        capacity.release("a");
        capacity.acquire("b");
        capacity.release("b");
        // the user of the cancelled import has no imports left to release
        assertThrows(IllegalStateException.class, () -> capacity.release("b"));
    }

    @Test
    public void cancelUserSlotWait() throws Exception {
        // the shared slots are free, the import waits for a slot of the user
        ImportCapacity capacity = new ImportCapacity(10, 10, 1, 10, Duration.ofSeconds(10), () -> 0, 0);
        capacity.acquire("a");

        ImportCapacity.Waiter waiter = new ImportCapacity.Waiter();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread waiting = startWaiting(capacity, "a", waiter, error);

        long start = System.nanoTime();
        waiter.cancel();
        waiting.join(5000);
        assertFalse(waiting.isAlive());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        assertTrue(error.get() instanceof ServiceUnavailableException, "unexpected error: " + error.get());

        // only the first import is left to release
        capacity.release("a");
        assertThrows(IllegalStateException.class, () -> capacity.release("a"));
        // and the user can import again
        capacity.acquire("a");
        capacity.release("a");
    }

    @Test
    public void cancelBeforeWaiting() throws Exception {
        ImportCapacity capacity = new ImportCapacity(1, 10, 10, 10, SHORT, () -> 0, 0);
        ImportCapacity.Waiter waiter = new ImportCapacity.Waiter();
        waiter.cancel();
        assertThrows(ServiceUnavailableException.class, () -> capacity.acquire("a", waiter));
        // a free slot is still available for others
        capacity.acquire("b");
    }

    @Test
    public void cancelAfterAcquire() throws Exception {
        ImportCapacity capacity = new ImportCapacity(1, 10, 10, 10, SHORT, () -> 0, 0);
        ImportCapacity.Waiter waiter = new ImportCapacity.Waiter();
        capacity.acquire("a", waiter);
        // too late to cancel, the import runs. This must not interrupt the thread.
        waiter.cancel();
        assertFalse(Thread.currentThread().isInterrupted());
        capacity.release("a");
    }

    /**
     * Start an acquire() in a new thread and return when it's waiting for a slot
     */
    private Thread startWaiting(ImportCapacity capacity, String username, ImportCapacity.Waiter waiter,
            AtomicReference<Throwable> error) throws InterruptedException {
        Thread waiting = new Thread(() -> {
            try {
                capacity.acquire(username, waiter);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        waiting.start();
        // wait until it's parked in tryAcquire()
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (waiting.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.nanoTime() < deadline, "the import didn't start waiting");
            Thread.sleep(1);
        }
        return waiting;
    }

    @Test
    public void diskSpace() throws Exception {
        ImportCapacity capacity = new ImportCapacity(1, 100, 1, 1, Duration.ZERO, () -> 100, 10);
        capacity.reserveDiskSpace(50);
        capacity.reserveDiskSpace(40);
        // would leave less than 10 bytes free
        assertThrows(ServiceUnavailableException.class, () -> capacity.reserveDiskSpace(1));

        capacity.releaseDiskSpace(40);
        capacity.reserveDiskSpace(40);
    }

    @Test
    public void diskSpaceTooLarge() {
        ImportCapacity capacity = new ImportCapacity(1, 100, 1, 1, Duration.ZERO, () -> 100, 10);
        assertThrows(ServiceUnavailableException.class, () -> capacity.reserveDiskSpace(91));
    }
}
