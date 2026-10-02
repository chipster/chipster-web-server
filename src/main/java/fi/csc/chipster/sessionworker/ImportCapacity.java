package fi.csc.chipster.sessionworker;

import java.io.File;
import java.time.Duration;
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
 * The XmlSession downloads the whole zip file to the local disk. Reserve the
 * disk space before the download, so that concurrent imports can't fill the
 * disk.
 *
 * All methods are thread-safe.
 */
public class ImportCapacity {

	public static final String CONF_MAX_CONCURRENT_IMPORTS = "session-worker-max-concurrent-imports";
	public static final String CONF_IMPORT_WAIT_TIMEOUT = "session-worker-import-wait-timeout";
	public static final String CONF_MIN_FREE_DISK_SPACE = "session-worker-min-free-disk-space";

	private static final Logger logger = LogManager.getLogger();

	private Semaphore slots;
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
				Duration.ofMinutes(config.getLong(CONF_IMPORT_WAIT_TIMEOUT)),
				tempDir::getUsableSpace,
				config.getLong(CONF_MIN_FREE_DISK_SPACE) * SessionLimits.MiB);
	}

	/**
	 * @param usableSpace free disk space in the temp dir in bytes
	 * @param minFreeSpace bytes to keep free
	 */
	public ImportCapacity(int maxConcurrentImports, Duration waitTimeout, LongSupplier usableSpace,
			long minFreeSpace) {
		if (maxConcurrentImports < 1) {
			throw new IllegalArgumentException(
					CONF_MAX_CONCURRENT_IMPORTS + " must be at least 1, was " + maxConcurrentImports);
		}
		// fair to start the imports in the order they arrived
		this.slots = new Semaphore(maxConcurrentImports, true);
		this.waitTimeout = waitTimeout;
		this.usableSpace = usableSpace;
		this.minFreeSpace = minFreeSpace;
	}

	/**
	 * Wait for a free import slot
	 *
	 * Call release() after the import, if this returns normally.
	 *
	 * @throws ServiceUnavailableException if there was no free slot within the
	 *                                     wait timeout or the wait was interrupted
	 */
	public void acquire() {
		try {
			if (!slots.tryAcquire(waitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new ServiceUnavailableException(
						"too many sessions are being imported at the moment, please try again later");
			}
		} catch (InterruptedException e) {
			/*
			 * Jetty interrupts its request threads when it stops. Don't restore the
			 * interrupt flag, because then the blocking writes of the response would fail
			 * too. Finishing the request with this error is the quickest way to give the
			 * thread back to Jetty.
			 */
			throw new ServiceUnavailableException("session import was interrupted, please try again later");
		}
	}

	public void release() {
		slots.release();
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
