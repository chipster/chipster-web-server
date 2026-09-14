package fi.csc.chipster.filestorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

		testBrokenFile();
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

		try (ReadaheadFileInputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize)) {

			assertEquals(fileSize, raStream.length());
			assertEquals(false, raStream.markSupported());

			// zero length read is zero bytes, not an error and not the end of the file
			assertEquals(0, raStream.read(new byte[10], 0, 0));

			assertEquals(0, raStream.skip(0));
			assertEquals(0, raStream.skip(-1));

			try {
				raStream.skip(1);
				fail("skip didn't throw");
			} catch (IOException e) {
				logger.info("expected exception", e);
			}

			assertEquals(fileSize, IOUtils.copyLarge(raStream, OutputStream.nullOutputStream()));

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
				new ReadaheadFileInputStream(new File(tempFile.getPath() + "-not-found"), queueLength, chunkSize);
				fail("missing file didn't throw");
			} catch (FileNotFoundException e) {
				logger.info("expected exception", e);
			}

			try {
				new ReadaheadFileInputStream(tempFile.getParentFile(), queueLength, chunkSize);
				fail("directory didn't throw");
			} catch (FileNotFoundException e) {
				logger.info("expected exception", e);
			}

			try {
				new ReadaheadFileInputStream(tempFile, 0, chunkSize);
				fail("zero queue length didn't throw");
			} catch (IllegalArgumentException e) {
				logger.info("expected exception", e);
			}

			try {
				// this would be truncated to a wrong chunk size
				new ReadaheadFileInputStream(tempFile, queueLength, (long) Integer.MAX_VALUE + 1);
				fail("too large chunk size didn't throw");
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

			try (InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize);
					InputStream dummyStream = new PatternInputStream(fileSize)) {

				assertEquals(true, IOUtils.contentEquals(raStream, dummyStream));
			}

			testSingleByteReads(tempFile, fileSize);

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

		try (InputStream raStream = new ReadaheadFileInputStream(file, queueLength, chunkSize);
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
	 * Test that closing the stream early doesn't leak threads
	 *
	 * This is how the stream is used when the client cancels a download: only a
	 * small part of the file is read before close(). The stream must then stop all
	 * its own threads, because each leaked thread keeps its chunks in memory too
	 * (queueLength * chunkSize bytes), which made the server run out of memory
	 * after enough cancelled downloads.
	 *
	 * Thread count is used as the indicator, because it's easier to measure
	 * reliably than the retained memory. Only the threads of this stream class are
	 * counted, recognized by their name, so that the threads of the JVM, the test
	 * framework and other tests don't matter.
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

				InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize);

				try {
					// read a little to make sure the stream really started
					assertTrue(raStream.read(new byte[copyBufferSize]) > 0);
				} finally {
					raStream.close();
				}
			}

			// threads are stopped asynchronously, so wait for them for a while
			Set<Thread> leaked = waitForThreadsToStop(threadsBefore, 10_000);

			for (Thread t : leaked) {
				logger.error("thread was not stopped: " + t.getName() + " " + t.getState());
			}

			assertEquals(0, leaked.size(),
					leaked.size() + " threads were left running after closing " + closeTestStreamCount + " streams");

		} finally {
			tempFile.delete();
		}
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

		try (InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize);
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
	private void testBrokenFile() throws IOException {

		File tempFile = null;
		long fileSize = chunkSize * queueLength * 2;

		try {

			tempFile = createFile(fileSize);

			try (InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize)) {

				// leave only the first chunk, so the later ones cannot be read
				try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
					raf.setLength(chunkSize);
				}

				try {
					IOUtils.copyLarge(raStream, OutputStream.nullOutputStream(), new byte[copyBufferSize]);
					fail("exception was not thrown");
				} catch (IOException e) {
					logger.info("expected exception", e);
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

			// reading a closed stream must fail too
			testClosedStream(fileSize);

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
	private void testClosedStream(long fileSize) throws IOException {

		File tempFile = createFile(fileSize);

		try {
			InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize);

			assertEquals(0, raStream.available());

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

			// closing twice is allowed
			raStream.close();

		} finally {
			tempFile.delete();
		}
	}
}
