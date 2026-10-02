package fi.csc.chipster.sessionworker;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.Config;
import jakarta.ws.rs.ServiceUnavailableException;

/**
 * Limit the resources used by all session imports together
 *
 * SessionLimits limits the memory of each import, but the session-worker would
 * still run out of memory, if there were enough imports at the same time.
 * Imports wait for a free slot, because the client may import several sessions
 * at the same time.
 *
 * Each user can run only a few imports at the same time, so that one user
 * can't take all the slots. The number of waiting imports of a user is limited
 * too, because each one holds a request thread.
 *
 * The XmlSession downloads the whole zip file to the local disk. Reserve the
 * disk space before the download, so that concurrent imports can't fill the
 * disk.
 *
 * All methods are thread-safe.
 */
public class ImportCapacity {

	public static final String CONF_MAX_CONCURRENT_IMPORTS = "session-worker-max-concurrent-imports";
	public static final String CONF_MAX_USER_CONCURRENT_IMPORTS = "session-worker-max-user-concurrent-imports";
	public static final String CONF_MAX_USER_IMPORTS = "session-worker-max-user-imports";
	public static final String CONF_IMPORT_WAIT_TIMEOUT = "session-worker-import-wait-timeout";
	public static final String CONF_MIN_FREE_DISK_SPACE = "session-worker-min-free-disk-space";

	private static final Logger logger = LogManager.getLogger();

	private Semaphore slots;
	private int maxUserConcurrentImports;
	private int maxUserImports;
	private HashMap<String, UserImports> users = new HashMap<>();
	private Duration waitTimeout;
	private LongSupplier usableSpace;
	private long minFreeSpace;
	private long reservedSpace = 0;

	/**
	 * Read the limits from the config, where the wait timeout is in minutes and
	 * the disk space in MiB
	 */
	public ImportCapacity(Config config, File tempDir) {
		this(config.getInt(CONF_MAX_CONCURRENT_IMPORTS),
				config.getInt(CONF_MAX_USER_CONCURRENT_IMPORTS),
				config.getInt(CONF_MAX_USER_IMPORTS),
				Duration.ofMinutes(config.getLong(CONF_IMPORT_WAIT_TIMEOUT)),
				tempDir::getUsableSpace,
				config.getLong(CONF_MIN_FREE_DISK_SPACE) * SessionLimits.MiB);
	}

	/**
	 * @param maxConcurrentImports     imports running at the same time, all users
	 *                                 together
	 * @param maxUserConcurrentImports imports of one user running at the same
	 *                                 time
	 * @param maxUserImports           imports of one user running or waiting.
	 *                                 More are refused right away.
	 * @param usableSpace              free disk space in the temp dir in bytes
	 * @param minFreeSpace             bytes to keep free
	 */
	public ImportCapacity(int maxConcurrentImports, int maxUserConcurrentImports, int maxUserImports,
			Duration waitTimeout, LongSupplier usableSpace, long minFreeSpace) {
		checkAtLeastOne(CONF_MAX_CONCURRENT_IMPORTS, maxConcurrentImports);
		checkAtLeastOne(CONF_MAX_USER_CONCURRENT_IMPORTS, maxUserConcurrentImports);
		checkAtLeastOne(CONF_MAX_USER_IMPORTS, maxUserImports);
		// fair to start the imports in the order they arrived
		this.slots = new Semaphore(maxConcurrentImports, true);
		this.maxUserConcurrentImports = maxUserConcurrentImports;
		this.maxUserImports = maxUserImports;
		this.waitTimeout = waitTimeout;
		this.usableSpace = usableSpace;
		this.minFreeSpace = minFreeSpace;
	}

	private static void checkAtLeastOne(String key, int value) {
		if (value < 1) {
			throw new IllegalArgumentException(key + " must be at least 1, was " + value);
		}
	}

	/**
	 * Imports of one user, running or waiting
	 */
	private static class UserImports {
		private Semaphore slots;
		private int count = 0;

		UserImports(int maxConcurrentImports) {
			this.slots = new Semaphore(maxConcurrentImports, true);
		}
	}

	/**
	 * Wait for a free import slot, first a slot of the user and then a shared
	 * one
	 *
	 * Call release() after the import, if this returns normally.
	 *
	 * @throws ServiceUnavailableException if the user has too many imports already,
	 *                                     there was no free slot within the wait
	 *                                     timeout or the wait was interrupted
	 */
	public void acquire(String username) {
		UserImports user;
		synchronized (users) {
			user = users.computeIfAbsent(username, u -> new UserImports(maxUserConcurrentImports));
			if (user.count >= maxUserImports) {
				throw new ServiceUnavailableException("you are already importing " + maxUserImports
						+ " sessions, please wait until they are ready");
			}
			user.count++;
		}

		boolean acquired = false;
		try {
			// both waits together must fit in the timeout
			long deadline = System.nanoTime() + waitTimeout.toNanos();

			if (!user.slots.tryAcquire(waitTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
				throw new ServiceUnavailableException(
						"your other sessions are still being imported, please try again later");
			}
			try {
				if (!slots.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
					throw new ServiceUnavailableException(
							"too many sessions are being imported at the moment, please try again later");
				}
			} catch (InterruptedException | RuntimeException e) {
				user.slots.release();
				throw e;
			}
			acquired = true;

		} catch (InterruptedException e) {
			/*
			 * Jetty interrupts its request threads when it stops. Don't restore the
			 * interrupt flag, because then the blocking writes of the response would fail
			 * too. Finishing the request with this error is the quickest way to give the
			 * thread back to Jetty.
			 */
			throw new ServiceUnavailableException("session import was interrupted, please try again later");
		} finally {
			if (!acquired) {
				removeImport(username, user);
			}
		}
	}

	public void release(String username) {
		synchronized (users) {
			UserImports user = users.get(username);
			if (user == null) {
				// a Semaphore would accept the extra permit and the limit would grow
				throw new IllegalStateException("no imports in progress for " + username);
			}
			slots.release();
			user.slots.release();
			removeImport(username, user);
		}
	}

	private void removeImport(String username, UserImports user) {
		synchronized (users) {
			user.count--;
			// only the imports in progress refer to the user's slots
			if (user.count == 0) {
				users.remove(username);
			}
		}
	}

	/**
	 * Reserve disk space for downloading a file
	 *
	 * Call releaseDiskSpace() when the download has ended, if this returns
	 * normally. Don't hold the reservation after that: the bytes are on the disk
	 * and the usable space has decreased accordingly, so they would be counted
	 * twice. The reservations of the other imports are subtracted from the usable
	 * space, even if they have already downloaded some of their files. This may
	 * refuse an import unnecessarily, but only while the other downloads are
	 * running.
	 *
	 * @throws ServiceUnavailableException if there isn't enough space
	 */
	public synchronized void reserveDiskSpace(long bytes) {
		long usable = usableSpace.getAsLong();
		if (usable - reservedSpace - bytes < minFreeSpace) {
			logger.warn("not enough disk space for the session import: usable " + usable + ", reserved "
					+ reservedSpace + ", requested " + bytes + ", keep free " + minFreeSpace);
			throw new ServiceUnavailableException(
					"not enough disk space to import this session at the moment, please try again later");
		}
		reservedSpace += bytes;
	}

	public synchronized void releaseDiskSpace(long bytes) {
		reservedSpace -= bytes;
	}
}
