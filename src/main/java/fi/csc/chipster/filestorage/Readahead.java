package fi.csc.chipster.filestorage;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.Config;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.NotFoundException;

/**
 * Download large files with readahead
 *
 * Reading a file with several threads is a lot faster on distributed storage
 * systems, see ReadaheadFileInputStream. This class keeps everything that the
 * file download needs for it: the configuration, the threads which all the
 * transfers share, and the limit for how many transfers may use it at the same
 * time.
 *
 * Readahead is disabled by default. FileServlet asks get() to send the file and
 * serves it in its own way if this returns false, so readahead can be removed by
 * deleting this class and its three calls.
 */
public class Readahead {

	private static final String CONF_KEY_ABOVE = "file-storage-readahead-above";
	private static final String CONF_KEY_CHUNK_SIZE = "file-storage-readahead-chunk-size";
	private static final String CONF_KEY_CHUNK_COUNT = "file-storage-readahead-chunk-count";
	private static final String CONF_KEY_MAX_CONCURRENT = "file-storage-readahead-max-concurrent";

	private static final Logger logger = LogManager.getLogger();

	// files smaller than this are not worth the threads. Readahead is disabled when
	// the configuration key is empty, which is kept as -1 here.
	private long above = -1;
	private long chunkSize;
	private int chunkCount;
	private int maxConcurrent;

	// limit the number of concurrent readahead transfers to limit memory usage
	private Semaphore semaphore;

	// threads for reading the chunks, shared by all the transfers
	private ExecutorService executor;

	public Readahead(Config config) {

		if (config.getString(CONF_KEY_ABOVE).isBlank()) {
			logger.info("readahead is disabled");
			return;
		}

		this.above = config.getLong(CONF_KEY_ABOVE) * 1024 * 1024;
		this.chunkSize = config.getLong(CONF_KEY_CHUNK_SIZE) * 1024 * 1024;
		this.chunkCount = config.getInt(CONF_KEY_CHUNK_COUNT);
		this.maxConcurrent = config.getInt(CONF_KEY_MAX_CONCURRENT);

		if (this.above < 0) {
			// -1 is the internal value for disabled, so a negative configuration would
			// enable readahead for every file instead
			throw new IllegalArgumentException(CONF_KEY_ABOVE + " must not be negative, but it was "
					+ this.above / 1024 / 1024 + " MiB. Leave it empty to disable readahead.");
		}

		// the stream checks the chunk size itself, but these two are ours: they tell
		// how many threads the pool needs
		if (this.maxConcurrent < 1) {
			throw new IllegalArgumentException(
					CONF_KEY_MAX_CONCURRENT + " must be at least 1, but it was " + this.maxConcurrent);
		}

		if (this.chunkCount < 1) {
			throw new IllegalArgumentException(
					CONF_KEY_CHUNK_COUNT + " must be at least 1, but it was " + this.chunkCount);
		}

		// there is no sensible limit for the number of threads, but it must fit in an
		// int, because otherwise the cast below would silently truncate it to a wrong
		// number. Calculate as a long for the same reason: two large values would
		// overflow an int, possibly to a negative number.
		long threadCount = (long) this.maxConcurrent * this.chunkCount;

		if (threadCount > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(CONF_KEY_MAX_CONCURRENT + " * " + CONF_KEY_CHUNK_COUNT + " would need "
					+ threadCount + " threads, which is more than this server can count");
		}

		this.semaphore = new Semaphore(this.maxConcurrent);

		// one pool for all the transfers. Each of them needs chunk-count threads and
		// the semaphore allows max-concurrent transfers at a time.
		this.executor = ReadaheadFileInputStream.createExecutor((int) threadCount);

		// the queue keeps chunk-count chunks, one more is being consumed and one more
		// can still be arriving when a transfer is closed
		long maxMemory = (long) this.maxConcurrent * (this.chunkCount + 2) * this.chunkSize;

		// the JVM keeps a native buffer of one chunk for each reading thread
		long maxNativeMemory = (long) this.maxConcurrent * this.chunkCount * this.chunkSize;

		logger.info("readahead enabled, JVM max heap: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MiB");
		logger.info("readahead chunk size: " + this.chunkSize / 1024 / 1024 + " MiB");
		logger.info("readahead chunk count: " + this.chunkCount);
		logger.info("readahead max concurrent transfers: " + this.maxConcurrent);
		logger.info("readahead max heap memory: about " + maxMemory / 1024 / 1024 + " MiB");
		logger.info("readahead max native memory: about " + maxNativeMemory / 1024 / 1024 + " MiB");
	}

	/**
	 * Send the file with readahead, if it's worth it and we have the resources
	 *
	 * This writes the response itself, so it declines range queries and conditional
	 * gets, which the servlet answers better. The caller must send the file in its
	 * own way when this returns false, which happens also when all the transfer
	 * slots are taken.
	 * 
	 * The slot, the memory and the thread of the caller are kept until the client
	 * has got the whole file, not only until the file is read from the storage,
	 * because the response is written at the speed of the client. Slow clients can
	 * keep all the slots, which makes the other downloads fall back to the slower
	 * way.
	 *
	 * @param file     File to send
	 * @param request  Request, to check that it isn't a range query
	 * @param response Response to write the file to
	 * @return true if the file was sent
	 * @throws IOException
	 */
	public boolean get(File file, HttpServletRequest request, HttpServletResponse response) throws IOException {

		if (this.above == -1 || file.length() < this.above || this.executor.isShutdown()) {
			return false;
		}

		// we write the response ourselves, so we can answer none of these: a range
		// query, a conditional get which could be a 304, or a precondition which
		// could be a 412. Let the caller send the file in its own way instead.
		if (request.getHeader("Range") != null || request.getHeader("If-Range") != null
				|| request.getHeader("If-None-Match") != null || request.getHeader("If-Match") != null
				|| request.getHeader("If-Modified-Since") != null
				|| request.getHeader("If-Unmodified-Since") != null) {
			return false;
		}

		// each transfer needs its own threads and buffers, so allow only a limited
		// number of them at the same time. Others are served without readahead, which
		// is slower, but doesn't need extra memory.
		if (!this.semaphore.tryAcquire()) {

			logger.info("readahead is already used by " + this.maxConcurrent
					+ " transfers, get file without readahead");

			return false;
		}

		try {
			logger.info("use readahead to get file of size " + file.length() / 1024 / 1024 + " MiB");

			try (ReadaheadFileInputStream fis = open(file)) {

				// set these only after the stream was opened, because the constructor
				// throws if the file was deleted already
				response.setContentType("application/octet-stream");
				// we decline the range queries ourselves, but the servlet answers them,
				// so the client can ask for a range in its next request
				response.setHeader("Accept-Ranges", "bytes");
				response.setStatus(HttpServletResponse.SC_OK);
				// the stream reads the length it saw when it was created, so asking the
				// file again could announce a different length than what we send
				response.setContentLengthLong(fis.length());

				try (OutputStream os = response.getOutputStream()) {

					// the stream writes whole chunks, no need to copy them again
					fis.transferTo(os);
				}
			}

			return true;

		} finally {
			this.semaphore.release();
		}
	}

	/**
	 * Open a file for a readahead download
	 *
	 * @param file File to read
	 * @return Stream to read the file
	 * @throws IOException
	 */
	private ReadaheadFileInputStream open(File file) throws IOException {

		try {
			return new ReadaheadFileInputStream(file, this.chunkCount, this.chunkSize, this.executor);

		} catch (FileNotFoundException | NoSuchFileException e) {
			// deleted after the check of the caller. The stream checks the file itself
			// too, but the file can disappear also between its check and open. Log the
			// reason, because the client gets only a 404 without one.
			logger.info("cannot open " + file + " for readahead: " + e);
			throw new NotFoundException("no such file");
		}
	}

	/**
	 * Stop the threads
	 * 
	 * The chunks which are requested already are still read, but a transfer in the
	 * middle of a file needs more of them, so it fails at the next chunk and the
	 * client gets less than the Content-Length promised. file-broker notices that.
	 * Waiting for the transfers instead would mean waiting for the clients, which
	 * can take as long as they like.
	 * 
	 * The threads are daemons, so they can't keep the JVM running either way.
	 */
	public void close() {

		if (this.executor != null) {
			this.executor.shutdown();
		}
	}
}
