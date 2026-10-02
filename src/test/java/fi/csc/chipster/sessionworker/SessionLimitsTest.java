package fi.csc.chipster.sessionworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.sessionworker.xml.SessionLoader;
import fi.csc.chipster.sessionworker.xml.SessionLoaderImpl2;

public class SessionLimitsTest {

	private static InputStream toStream(String str) {
		return new ByteArrayInputStream(str.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void defaults() {
		SessionLimits limits = new SessionLimits(new Config());
		// config is in MiB
		assertEquals(4l * 1024 * 1024, limits.getMaxXmlCheckSize());
	}

	@Test
	public void xmlAtLimit() throws Exception {
		SessionLimits limits = new SessionLimits(0, 10, 0, 0);
		assertEquals("0123456789", IOUtils.toString(limits.limitXml(toStream("0123456789")), StandardCharsets.UTF_8));
	}

	@Test
	public void xmlOverLimit() {
		SessionLimits limits = new SessionLimits(0, 10, 0, 0);
		assertThrows(ZipException.class, () -> IOUtils.toByteArray(limits.limitXml(toStream("0123456789a"))));
	}

	@Test
	public void metadataAtLimit() throws Exception {
		SessionLimits limits = new SessionLimits(0, 0, 10, 0);
		assertEquals("01234", limits.readMetadata(toStream("01234"), "a"));
		assertEquals("56789", limits.readMetadata(toStream("56789"), "b"));
	}

	@Test
	public void metadataOverTotalLimit() throws Exception {
		SessionLimits limits = new SessionLimits(0, 0, 10, 0);
		limits.readMetadata(toStream("01234"), "a");
		// each entry is below the limit, but the total isn't
		assertThrows(ZipException.class, () -> limits.readMetadata(toStream("567890"), "b"));
	}

	@Test
	public void entriesAtLimit() throws Exception {
		SessionLimits limits = new SessionLimits(0, 0, 0, 2);
		limits.checkEntryCount(2);
		limits.countEntry();
		limits.countEntry();
	}

	@Test
	public void entriesOverLimit() throws Exception {
		SessionLimits limits = new SessionLimits(0, 0, 0, 2);
		assertThrows(ZipException.class, () -> limits.checkEntryCount(3));
		limits.countEntry();
		limits.countEntry();
		assertThrows(ZipException.class, () -> limits.countEntry());
	}

	/**
	 * Parsers wrap the exception, but the servlet shows the error to the user only
	 * if the root cause is ZipException
	 */
	@Test
	public void xmlOverLimitInParser() {
		SessionLimits limits = new SessionLimits(0, 20, 0, 0);
		String xml = "<session format-version=\"2\"><notes>too long</notes></session>";

		Exception e = assertThrows(Exception.class, () -> SessionLoaderImpl2.parseXml(limits.limitXml(toStream(xml))));
		assertInstanceOf(ZipException.class, ExceptionUtils.getRootCause(e), ExceptionUtils.getStackTrace(e));

		e = assertThrows(Exception.class, () -> SessionLoader.getSessionVersion(limits.limitXml(toStream(xml))));
		assertInstanceOf(ZipException.class, ExceptionUtils.getRootCause(e), ExceptionUtils.getStackTrace(e));
		assertTrue(ExceptionUtils.getRootCause(e).getMessage().contains("session.xml"));
	}
}
