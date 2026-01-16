package fi.csc.chipster.filestorage;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

	@Test
	public void test() {

		File tempFile = null;
		long fileSize = 100;

		try {

			tempFile = File.createTempFile(this.getClass().getSimpleName() + "-test-file-", "");

			try (InputStream in = new FileResourceTest.DummyInputStream(fileSize);
					OutputStream out = new FileOutputStream(tempFile)) {
				IOUtils.copyLarge(in, out, new byte[1 << 16]);
			}

			assertEquals(true,
					IOUtils.contentEquals(new ReadaheadFileInputStream(tempFile),
							new FileResourceTest.DummyInputStream(fileSize)));
		} catch (IOException e) {
			logger.error("test failed with size " + fileSize);
		} finally {
			if (tempFile != null) {
				tempFile.delete();
			}
		}
	}

}
