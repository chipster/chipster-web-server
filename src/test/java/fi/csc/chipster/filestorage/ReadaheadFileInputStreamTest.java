package fi.csc.chipster.filestorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.filebroker.FileResourceTest;

public class ReadaheadFileInputStreamTest {

	private Logger logger = LogManager.getLogger();

	private long chunkSize = 1 << 20; // 1 MiB
	private int queueLength = 4;
	private int copyBufferSize = 1024;
	private int closeTestStreamCount = 20;

	@Test
	public void test() {

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

		try {
			testBrokenFile();
			fail("exception was not thrown");
		} catch (RuntimeException e) {
			logger.info("expected exception", e);
			// expected
		}
	}

	private void testSize(long fileSize) {

		File tempFile = null;

		try {

			tempFile = createFile(fileSize);

			assertEquals(true,
					IOUtils.contentEquals(
							new ReadaheadFileInputStream(tempFile, queueLength, chunkSize),
							new FileResourceTest.DummyInputStream(fileSize)));
		} catch (IOException e) {
			logger.error("test failed with size " + fileSize);
		} finally {
			if (tempFile != null) {
				tempFile.delete();
			}
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
	 * reliably than the retained memory. The count is compared to the situation
	 * before the test, so that threads of the JVM and other tests don't matter.
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

				// read a little to get the reading threads started
				assertTrue(raStream.read(new byte[copyBufferSize]) > 0);

				raStream.close();
			}

			// threads are stopped asynchronously, so wait for them for a while
			Set<Thread> leaked = waitForThreadsToStop(threadsBefore, 10_000);

			for (Thread t : leaked) {
				logger.error("thread was not stopped: " + t.getName() + " " + t.getState());
			}

			// allow some slack for threads of the last stream and the test framework
			assertTrue(leaked.size() <= queueLength + 2,
					leaked.size() + " threads were left running after closing " + closeTestStreamCount + " streams");

		} finally {
			tempFile.delete();
		}
	}

	private Set<Thread> getThreads() {
		return new HashSet<Thread>(Thread.getAllStackTraces().keySet());
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

			if (newThreads.size() <= queueLength + 2 || System.currentTimeMillis() > deadline) {
				return newThreads;
			}

			Thread.sleep(100);
		}
	}

	private File createFile(long fileSize) throws IOException {
		File tempFile = File.createTempFile(this.getClass().getSimpleName() + "-test-file-", "");

		createFile(tempFile, fileSize);

		return tempFile;
	}

	private void createFile(File file, long fileSize) throws IOException {

		try (InputStream in = new FileResourceTest.DummyInputStream(fileSize);
				OutputStream out = new FileOutputStream(file)) {
			IOUtils.copyLarge(in, out, new byte[copyBufferSize]);
		}
	}

	/**
	 * Test that we get an exception when the file is deleted during the reading
	 * 
	 * This relies on the implementation decision of the ReadaheadFileInputStream to
	 * constantly open new files. Plain FileInputSteam wouldn't even break, because
	 * the inode will stay around. If this ever changes, find some other way to
	 * break the stream.
	 * 
	 */
	private void testBrokenFile() {

		File tempFile = null;
		long fileSize = chunkSize * queueLength * 2;

		try {

			tempFile = createFile(fileSize);

			try (InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize)) {

				tempFile.delete();

				IOUtils.copyLarge(raStream, OutputStream.nullOutputStream(), new byte[copyBufferSize]);
			}
		} catch (IOException e) {
			logger.error("test failed with size " + fileSize);
		} finally {
			if (tempFile != null && tempFile.exists()) {
				tempFile.delete();
			}
		}
	}
}
