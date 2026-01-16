package fi.csc.chipster.filestorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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

	@Test
	public void test() {
		testDirectMemory(true);
		testDirectMemory(false);
	}

	private void testDirectMemory(boolean useDirectMemory) {

		testSize(useDirectMemory, 0);
		testSize(useDirectMemory, 1);

		testSize(useDirectMemory, chunkSize - 1);
		testSize(useDirectMemory, chunkSize);
		testSize(useDirectMemory, chunkSize + 1);

		testSize(useDirectMemory, chunkSize - copyBufferSize - 1);
		testSize(useDirectMemory, chunkSize - copyBufferSize);
		testSize(useDirectMemory, chunkSize - copyBufferSize + 1);

		testSize(useDirectMemory, chunkSize + copyBufferSize - 1);
		testSize(useDirectMemory, chunkSize + copyBufferSize);
		testSize(useDirectMemory, chunkSize + copyBufferSize + 1);

		testSize(useDirectMemory, chunkSize * 2 - 1);
		testSize(useDirectMemory, chunkSize * 2);
		testSize(useDirectMemory, chunkSize * 2 + 1);

		testSize(useDirectMemory, chunkSize * queueLength - 1);
		testSize(useDirectMemory, chunkSize * queueLength);
		testSize(useDirectMemory, chunkSize * queueLength + 1);

		try {
			testBrokenFile(useDirectMemory);
			fail("exception was not thrown");
		} catch (RuntimeException e) {
			logger.info("expected exception", e);
			// expected
		}
	}

	private void testSize(boolean useDirectMemory, long fileSize) {

		File tempFile = null;

		try {

			tempFile = createFile(fileSize);

			assertEquals(true,
					IOUtils.contentEquals(
							new ReadaheadFileInputStream(tempFile, queueLength, chunkSize, useDirectMemory),
							new FileResourceTest.DummyInputStream(fileSize)));
		} catch (IOException e) {
			logger.error("test failed with size " + fileSize);
		} finally {
			if (tempFile != null) {
				tempFile.delete();
			}
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
	 * @param useDirectMemory
	 */
	private void testBrokenFile(boolean useDirectMemory) {

		File tempFile = null;
		long fileSize = chunkSize * queueLength * 2;

		try {

			tempFile = createFile(fileSize);

			try (InputStream raStream = new ReadaheadFileInputStream(tempFile, queueLength, chunkSize,
					useDirectMemory)) {

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
