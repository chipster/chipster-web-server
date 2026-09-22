package fi.csc.chipster.filestorage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


public class ReadaheadFileInputStreamTest {

	private Logger logger = LogManager.getLogger();

	private long chunkSize = 1 << 20; // 1 MiB
	private int queueLength = 4;
	private int copyBufferSize = 1024;
	private int closeTestStreamCount = 20;

	/**
	 * Period of the test data pattern
	 * 
	 * Must not divide the chunk size. Otherwise all the chunks would be identical
	 * and the tests couldn't notice if the chunks were returned in a wrong order,
	 * duplicated or skipped, which is exactly what this stream has to get right.
	 */
	private static final int PATTERN_PERIOD = 251;

	// the streams share the threads, so the tests need a pool of their own
	private ExecutorService executor;

	@BeforeEach
	public void createExecutor() {
		// more threads than one stream needs, so that the tests would notice also a
		// stream which requests more chunks than its queue length allows
		this.executor = ReadaheadFileInputStream.createExecutor(queueLength * 2);
	}

	@AfterEach
	public void shutdownExecutor() {
		this.executor.shutdownNow();
	}

	/**
	 * Test data where each byte tells its own position
	 */
	public static class PatternInputStream extends InputStream {

		private long position = 0;
		private long size;

		public PatternInputStream(long size) {
			this.size = size;
		}

		public static int byteAt(long position) {
			return (int) (position % PATTERN_PERIOD);
		}

		@Override
		public int read() {
			if (position < size) {
				return byteAt(position++);
			} else {
				return -1;
			}
		}
	}

	@Test
	public void test() throws IOException {

		testSize(0);
		testSize(1);

		testSize(chunkSize - 1);
		testSize(chunkSize);
		testSize(chunkSize + 1);

		testSize(chunkSize - copyBufferSize - 1);
		testSize(chunkSize - copyBufferSize);
		testSize(chunkSize - copyBufferSize + 1);

		testSize(chunkSize + copyBufferSize - 1);
		testSize(chunkSize + copyBufferSize);
		testSize(chunkSize + copyBufferSize + 1);

		testSize(chunkSize * 2 - 1);
		testSize(chunkSize * 2);
		testSize(chunkSize * 2 + 1);

		testSize(chunkSize * queueLength - 1);
		testSize(chunkSize * queueLength);
		testSize(chunkSize * queueLength + 1);

	}

	/**
	 * Check the test data itself
	 * 
	 * If the pattern period divided the chunk size, all the chunks would look the
	 * same and the other tests couldn't notice chunks in a wrong order.
	 */
	@Test
	public void patternPeriod() {

		assertEquals(false, PatternInputStream.byteAt(0) == PatternInputStream.byteAt(chunkSize),
				"the pattern period divides the chunk size");
	}

	/**
	 * Test the details of the InputStream contract
	 * 
	 * @throws IOException
	 */
	@Test
	public void contract() throws IOException {

		long fileSize = chunkSize + 1;

		File tempFile = createFile(fileSize);

		try (ReadaheadFileInputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor)) {

			assertEquals(fileSize, raStream.length());
			assertEquals(false, raStream.markSupported());

			// zero length read is zero bytes, not an error and not the end of the file
			assertEquals(0, raStream.read(new byte[10], 0, 0));

			// the array and the range are checked also when nothing is read. The buffer
			// of the stream checks them when it really reads, so these must have the
			// length of zero to test our own check
			try {
				raStream.read(new byte[10], 20, 0);
				fail("offset past the end of the array didn't throw");
			} catch (IndexOutOfBoundsException e) {
				logger.info("expected exception", e);
			}

			try {
				raStream.read(new byte[10], -1, 0);
				fail("negative offset didn't throw");
			} catch (IndexOutOfBoundsException e) {
				logger.info("expected exception", e);
			}

			// read to a position of our own choosing
			byte[] buffer = new byte[10];
			assertEquals(3, raStream.read(buffer, 5, 3));
			// the pattern is an unsigned value, the array is signed
			assertEquals(PatternInputStream.byteAt(0), buffer[5] & 0xff);
			assertEquals(PatternInputStream.byteAt(1), buffer[6] & 0xff);
			assertEquals(PatternInputStream.byteAt(2), buffer[7] & 0xff);
			// the bytes outside the range must not change
			assertEquals(0, buffer[4]);
			assertEquals(0, buffer[8]);

			// the rest of the first chunk is available without new reads
			assertEquals(chunkSize - 3, raStream.available());

			// the inherited skip() reads and throws away
			assertEquals(10, raStream.skip(10));
			assertEquals(PatternInputStream.byteAt(13), raStream.read());

			assertEquals(fileSize - 14, IOUtils.copyLarge(raStream, OutputStream.nullOutputStream()));

			// the end of the file must not change these
			assertEquals(0, raStream.read(new byte[10], 0, 0));
			assertEquals(-1, raStream.read());
			assertEquals(0, raStream.available());

		} finally {
			tempFile.delete();
		}
	}

	/**
	 * Test that bad parameters are noticed when the stream is created
	 * 
	 * @throws IOException
	 */
	@Test
	public void invalidParameters() throws IOException {

		File tempFile = createFile(chunkSize);

		try {
			try {
				new ReadaheadFileInputStream(new File(tempFile.getPath() + "-not-found"), queueLength, chunkSize, executor);
				fail("missing file didn't throw");
			} catch (FileNotFoundException e) {
				logger.info("expected exception", e);
			}

			try {
				new ReadaheadFileInputStream(tempFile.getParentFile(), queueLength, chunkSize, executor);
				fail("directory didn't throw");
			} catch (FileNotFoundException e) {
				logger.info("expected exception", e);
			}

			try {
				new ReadaheadFileInputStream(tempFile, 0, chunkSize, executor);
				fail("zero queue length didn't throw");
			} catch (IllegalArgumentException e) {
				logger.info("expected exception", e);
			}

			try {
				// this would be truncated to a wrong chunk size
				new ReadaheadFileInputStream(tempFile, queueLength, (long) Integer.MAX_VALUE + 1, executor);
				fail("too large chunk size didn't throw");
			} catch (IllegalArgumentException e) {
				logger.info("expected exception", e);
			}

			try {
				new ReadaheadFileInputStream(tempFile, queueLength, 0, executor);
				fail("zero chunk size didn't throw");
			} catch (IllegalArgumentException e) {
				logger.info("expected exception", e);
			}

		} finally {
			tempFile.delete();
		}
	}

	private void testSize(long fileSize) {

		File tempFile = null;

		try {

			tempFile = createFile(fileSize);

			try (InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor);
					InputStream dummyStream = new PatternInputStream(fileSize)) {

				assertEquals(true, IOUtils.contentEquals(raStream, dummyStream));
			}

			testSingleByteReads(tempFile, fileSize);
			testTransferTo(tempFile, fileSize);

		} catch (IOException e) {
			// don't hide the failure, the stream is expected to throw IOExceptions
			fail("test failed with size " + fileSize, e);
		} finally {
			if (tempFile != null) {
				tempFile.delete();
			}
		}
	}

	/**
	 * Test transferTo(), which FileServlet and RCat use
	 * 
	 * It writes whole chunks, unlike the read() methods, so it has to be tested
	 * separately.
	 * 
	 * @param file     File to read
	 * @param fileSize Expected number of bytes
	 * @throws IOException
	 */
	private void testTransferTo(File file, long fileSize) throws IOException {

		try (ReadaheadFileInputStream raStream = new ReadaheadFileInputStream(file, queueLength, chunkSize,
				executor)) {

			PatternOutputStream out = new PatternOutputStream();

			assertEquals(fileSize, raStream.transferTo(out));
			assertEquals(fileSize, out.getPosition());

			// nothing more to transfer after the end
			assertEquals(0, raStream.transferTo(out));
		}
	}

	/**
	 * Check that the bytes come in the right order and none are missing
	 */
	public static class PatternOutputStream extends OutputStream {

		private long position = 0;

		public long getPosition() {
			return position;
		}

		@Override
		public void write(int b) {
			assertEquals(PatternInputStream.byteAt(position), b & 0xff, "wrong byte in position " + position);
			position++;
		}

		@Override
		public void write(byte[] b, int off, int len) {
			for (int i = 0; i < len; i++) {
				write(b[off + i] & 0xff);
			}
		}
	}

	/**
	 * Test the single byte read()
	 * 
	 * IOUtils.contentEquals() uses only read(byte[], int, int) of the stream under
	 * test, so the single byte read() would be left without any coverage.
	 * 
	 * @param file     File to read
	 * @param fileSize Expected number of bytes
	 * @throws IOException
	 */
	private void testSingleByteReads(File file, long fileSize) throws IOException {

		try (InputStream raStream = new ReadaheadFileInputStream(file, queueLength, chunkSize, executor);
				InputStream dummyStream = new PatternInputStream(fileSize)) {

			long count = 0;
			int b;

			while ((b = raStream.read()) != -1) {
				assertEquals(dummyStream.read(), b);
				count++;
			}

			assertEquals(fileSize, count);
			// both streams must end at the same position
			assertEquals(-1, dummyStream.read());
			// reading again after the end must still report the end
			assertEquals(-1, raStream.read());
		}
	}

	/**
	 * Test closing the stream before the end of the file
	 *
	 * This is how the stream is used when the client cancels a download: only a
	 * small part of the file is read before close(). Originally each of those left
	 * a thread running, which kept its chunks in memory too, and the server ran out
	 * of memory after enough cancelled downloads. That thread is gone, so this
	 * checks what is left to check: a stream must not shut down the pool which the
	 * other streams use, and the threads must stop when the pool is shut down.
	 *
	 * The threads are counted also to check that the chunks are
	 * really read
	 * in parallel, and that they are gone when the pool is shut down. Only the
	 * threads of this stream class are counted, recognized by their name, so that
	 * the threads of the JVM, the test framework and other tests don't matter.
	 */
	@Test
	public void closeBeforeEndOfFile() throws IOException, InterruptedException {

		// the file must be larger than the readahead window, otherwise the stream
		// completes its reads on its own and there is nothing to clean up
		long fileSize = chunkSize * queueLength * 4;

		File tempFile = createFile(fileSize);

		try {
			Set<Thread> threadsBefore = getThreads();

			for (int i = 0; i < closeTestStreamCount; i++) {

				InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor);

				try {
					if (i == 0) {
						// the constructor must request the whole queue, which is the point
						// of this class. The pool creates a thread for each request while
						// it has fewer threads than requests, so the number of threads
						// tells how many requests were made. The pool has more threads
						// than this stream needs, so this notices too few and too many
						// requests alike. Count before reading, because the first read
						// requests one more chunk to keep the queue full.
						Set<Thread> threads = getThreads();
						threads.removeAll(threadsBefore);

						assertEquals(queueLength, threads.size(),
								"readahead requested " + threads.size() + " chunks, expected " + queueLength);

						for (Thread thread : threads) {
							// a forgotten stream must not keep the JVM running
							assertEquals(true, thread.isDaemon(), "thread " + thread.getName() + " is not a daemon");
						}
					}

					// read a little to make sure the stream really started
					assertTrue(raStream.read(new byte[copyBufferSize]) > 0);

				} finally {
					raStream.close();
				}
			}

			// the threads are shared, so a closed stream must leave the pool usable
			try (InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor)) {
				assertTrue(raStream.read(new byte[copyBufferSize]) > 0);
			}

			executor.shutdownNow();

			// threads are stopped asynchronously, so wait for them for a while
			Set<Thread> leaked = waitForThreadsToStop(threadsBefore, 10_000);

			for (Thread t : leaked) {
				logger.error("thread was not stopped: " + t.getName() + " " + t.getState());
			}

			assertEquals(0, leaked.size(), leaked.size() + " threads were left running after the pool was shut down");

		} finally {
			tempFile.delete();
		}
	}

	/**
	 * Test that close() closes the file
	 * 
	 * The file is read to the end first, so that no read is in progress when the
	 * stream is closed. Otherwise the interrupt of close() would close the channel
	 * anyway and this wouldn't notice if close() forgot the file.
	 * 
	 * @throws IOException
	 */
	@Test
	public void closeReleasesFile() throws IOException {

		// several chunks, so that the stream really uses its queue
		File tempFile = createFile(chunkSize * queueLength * 2);

		try {
			long openFilesBefore = getOpenFileCount();

			assumeTrue(openFilesBefore != -1, "this JVM doesn't tell the number of open files");

			// keep the streams, because the garbage collector would close the files of
			// the collected ones and hide a leak
			List<InputStream> streams = new ArrayList<>();

			for (int i = 0; i < closeTestStreamCount; i++) {
				InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor);
				streams.add(raStream);
				IOUtils.copyLarge(raStream, OutputStream.nullOutputStream(), new byte[copyBufferSize]);
				raStream.close();
			}

			long openFiles = getOpenFileCount();

			// tolerance for files opened by the other tests and the threads of the JVM,
			// still a lot less than the closeTestStreamCount files of a leak
			assertEquals(true, openFiles <= openFilesBefore + closeTestStreamCount / 2,
					"open files grew from " + openFilesBefore + " to " + openFiles + " after closing "
							+ closeTestStreamCount + " streams");

		} finally {
			tempFile.delete();
		}
	}

	/**
	 * Get the number of open files of this process
	 * 
	 * @return Number of open file descriptors, or -1 if the JVM doesn't tell
	 */
	private long getOpenFileCount() {

		OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

		if (os instanceof com.sun.management.UnixOperatingSystemMXBean) {
			return ((com.sun.management.UnixOperatingSystemMXBean) os).getOpenFileDescriptorCount();
		}

		return -1;
	}

	/**
	 * Get the live threads of ReadaheadFileInputStream
	 * 
	 * @return Threads recognized by their name
	 */
	private Set<Thread> getThreads() {

		Set<Thread> threads = new HashSet<Thread>();

		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.getName().startsWith(ReadaheadFileInputStream.THREAD_NAME_PREFIX)) {
				threads.add(thread);
			}
		}

		return threads;
	}

	/**
	 * Wait until all new threads have stopped
	 *
	 * @param threadsBefore Threads that were running before the test
	 * @param timeout       How long to wait, in milliseconds
	 * @return New threads that are still alive
	 * @throws InterruptedException
	 */
	private Set<Thread> waitForThreadsToStop(Set<Thread> threadsBefore, long timeout) throws InterruptedException {

		long deadline = System.currentTimeMillis() + timeout;
		Set<Thread> newThreads = null;

		while (true) {

			newThreads = getThreads();
			newThreads.removeAll(threadsBefore);
			newThreads.removeIf(t -> !t.isAlive());

			if (newThreads.isEmpty() || System.currentTimeMillis() > deadline) {
				return newThreads;
			}

			Thread.sleep(100);
		}
	}

	/**
	 * Test that a failing output doesn't lose or duplicate data
	 * 
	 * This is how a download ends when the client disconnects in the middle: the
	 * response refuses to take more bytes. The stream must then still have the
	 * chunk which wasn't written, not skip to the next one. This output refuses a
	 * whole chunk, while a real one could take a part of it first and leave those
	 * bytes in the stream too, which nobody reads anymore.
	 * 
	 * @throws IOException
	 */
	@Test
	public void transferToFailingOutput() throws IOException {

		long fileSize = chunkSize * queueLength * 2;

		File tempFile = createFile(fileSize);

		try (ReadaheadFileInputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize,
				executor)) {

			// refuse everything after the first two chunks
			FailingOutputStream out = new FailingOutputStream(chunkSize * 2);

			try {
				raStream.transferTo(out);
				fail("the failing output didn't throw");
			} catch (IOException e) {
				logger.info("expected exception", e);
			}

			assertEquals(chunkSize * 2, out.getWritten());

			// the chunk which the output refused must still be in the stream
			assertEquals(fileSize - out.getWritten(),
					IOUtils.copyLarge(raStream, OutputStream.nullOutputStream(), new byte[copyBufferSize]));
		} finally {
			tempFile.delete();
		}
	}

	/**
	 * OutputStream which takes only a limited number of bytes
	 */
	public static class FailingOutputStream extends OutputStream {

		private long limit;
		private long written = 0;

		public FailingOutputStream(long limit) {
			this.limit = limit;
		}

		public long getWritten() {
			return written;
		}

		@Override
		public void write(int b) throws IOException {
			write(new byte[] { (byte) b }, 0, 1);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {

			if (written + len > limit) {
				// like a client which isn't listening anymore
				throw new IOException("the output is gone");
			}

			written += len;
		}
	}

	/**
	 * Test a pool which is already shut down
	 * 
	 * The pool is shared and stopped when the server stops, so a download can be
	 * starting at that moment. The stream must report it like any other read error,
	 * i.e. with an IOException, which the caller expects and logs.
	 * 
	 * @throws IOException
	 */
	@Test
	public void shutDownExecutor() throws IOException {

		File tempFile = createFile(chunkSize * queueLength * 2);

		try {
			executor.shutdownNow();

			try {
				new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor);
				fail("a shut down pool didn't throw");

			} catch (FileNotFoundException e) {
				// this is an IOException too, but it would mean that the test file is
				// missing, not that the pool was noticed
				fail("the test file was missing", e);

			} catch (IOException e) {
				logger.info("expected exception", e);
			}

		} finally {
			tempFile.delete();
		}
	}

	/**
	 * Test two streams which share the threads
	 * 
	 * The pool is shared by all the transfers, so the streams must not disturb each
	 * other: the chunks of one stream must not end up in the other, and closing one
	 * must not stop the other.
	 * 
	 * @throws IOException
	 */
	@Test
	public void twoStreamsShareTheThreads() throws IOException {

		long fileSize = chunkSize * queueLength * 2;

		File tempFile = createFile(fileSize);

		try (InputStream first = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor);
				InputStream second = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor);
				InputStream firstExpected = new PatternInputStream(fileSize);
				InputStream secondExpected = new PatternInputStream(fileSize)) {

			byte[] buffer = new byte[copyBufferSize];
			byte[] expected = new byte[copyBufferSize];

			// read only one chunk, so that the second stream has to request more chunks
			// after the first one is closed, i.e. really needs the shared threads
			long readSize = chunkSize;

			// read both at the same time, a little from each in turn
			for (long position = 0; position < readSize; position += copyBufferSize) {

				for (InputStream[] pair : new InputStream[][] { { first, firstExpected },
						{ second, secondExpected } }) {

					int bytes = pair[0].readNBytes(buffer, 0, copyBufferSize);
					assertEquals(copyBufferSize, pair[1].readNBytes(expected, 0, copyBufferSize));
					assertEquals(copyBufferSize, bytes);
					assertArrayEquals(expected, buffer, "wrong data in position " + position);
				}
			}

			// closing one must leave the other usable, i.e. it must not stop the threads
			// which the second one still needs
			first.close();

			assertEquals(fileSize - readSize, IOUtils.copyLarge(second, OutputStream.nullOutputStream()));

		} finally {
			tempFile.delete();
		}
	}

	/**
	 * Test that the file can be deleted while it's being read
	 * 
	 * The file is opened only once, when the stream is created, so the data stays
	 * on the disk until the stream is closed. This is how a plain FileInputStream
	 * behaves too, and file-storage deletes files while they may still be
	 * downloaded.
	 * 
	 * @throws IOException
	 */
	@Test
	public void deleteDuringRead() throws IOException {

		// more chunks than the queue, so that most of them are read after the delete
		long fileSize = chunkSize * queueLength * 2;

		File tempFile = createFile(fileSize);

		try (InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor);
				InputStream dummyStream = new PatternInputStream(fileSize)) {

			assertEquals(true, tempFile.delete());
			assertEquals(false, tempFile.exists());

			assertEquals(true, IOUtils.contentEquals(raStream, dummyStream));

		} finally {
			tempFile.delete();
		}
	}

	private File createFile(long fileSize) throws IOException {
		File tempFile = File.createTempFile(this.getClass().getSimpleName() + "-test-file-", "");

		createFile(tempFile, fileSize);

		return tempFile;
	}

	private void createFile(File file, long fileSize) throws IOException {

		try (InputStream in = new PatternInputStream(fileSize);
				OutputStream out = new FileOutputStream(file)) {
			IOUtils.copyLarge(in, out, new byte[copyBufferSize]);
		}
	}

	/**
	 * Test that we get an exception when the file is truncated during the reading
	 * 
	 * The stream keeps the file open, so deleting it wouldn't break anything, see
	 * deleteDuringRead(). Truncating it does, because the chunks after the new end
	 * of the file cannot be read anymore.
	 * 
	 * @throws IOException
	 */
	@Test
	public void testBrokenFile() throws IOException {

		File tempFile = null;
		long fileSize = chunkSize * queueLength * 2;

		try {

			tempFile = createFile(fileSize);

			try (InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor)) {

				// leave only the first chunk, so the later ones cannot be read
				try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
					raf.setLength(chunkSize);
				}

				try {
					IOUtils.copyLarge(raStream, OutputStream.nullOutputStream(), new byte[copyBufferSize]);
					fail("exception was not thrown");
				} catch (IOException e) {
					logger.info("expected exception", e);

					// the real reason must not be lost on the way
					assertEquals(true,
							ExceptionUtils.getThrowableList(e).stream().anyMatch(t -> t instanceof EOFException),
							"the exception doesn't tell that the file ended: " + ExceptionUtils.getMessage(e));
				}

				// the first error must be remembered. Otherwise this read would continue
				// from the next chunk, i.e. return data from a wrong position without any
				// error.
				try {
					raStream.read(new byte[copyBufferSize]);
					fail("exception was not thrown after the first error");
				} catch (IOException e) {
					logger.info("expected exception", e);
				}

				// available() must not claim that everything is fine either
				try {
					raStream.available();
					fail("available() didn't throw after the first error");
				} catch (IOException e) {
					logger.info("expected exception", e);
				}
			}

		} finally {
			if (tempFile != null && tempFile.exists()) {
				tempFile.delete();
			}
		}
	}

	/**
	 * Test that a closed stream doesn't pretend to be at the end of the file
	 * 
	 * @param fileSize Size of the test file
	 * @throws IOException
	 */
	@Test
	public void testClosedStream() throws IOException {

		File tempFile = createFile(chunkSize * queueLength * 2);

		try {
			InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, executor);

			assertEquals(0, raStream.available());

			// read to the end before closing. Otherwise the reads below would fail
			// because the queue is empty, which would pass these tests even if the
			// stream didn't notice that it's closed
			IOUtils.copyLarge(raStream, OutputStream.nullOutputStream(), new byte[copyBufferSize]);

			raStream.close();

			try {
				raStream.read();
				fail("read of a closed stream didn't throw");
			} catch (IOException e) {
				logger.info("expected exception", e);
			}

			try {
				raStream.available();
				fail("available() of a closed stream didn't throw");
			} catch (IOException e) {
				logger.info("expected exception", e);
			}

			// BufferedInputStream checks the closed stream before the zero length too
			try {
				raStream.read(new byte[10], 0, 0);
				fail("zero length read of a closed stream didn't throw");
			} catch (IOException e) {
				logger.info("expected exception", e);
			}

			// closing twice is allowed
			raStream.close();

		} finally {
			tempFile.delete();
		}
	}
}
