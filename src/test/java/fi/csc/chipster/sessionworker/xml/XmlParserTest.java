package fi.csc.chipster.sessionworker.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

import fi.csc.chipster.util.XmlUtil;
import jakarta.xml.bind.JAXBException;

/**
 * The XML parsers must not accept DOCTYPE declarations, because they allow XXE
 * attacks with user uploaded session files
 */
public class XmlParserTest {

	private static final String SESSION = "<session format-version=\"2\"/>";
	private static final String SESSION_WITH_DOCTYPE = "<!DOCTYPE session>" + SESSION;

	private static InputStream toStream(String str) {
		return new ByteArrayInputStream(str.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void xmlUtil() throws Exception {
		assertEquals("session", XmlUtil.parseReader(new StringReader(SESSION)).getDocumentElement().getLocalName());
	}

	@Test
	public void xmlUtilDoctype() {
		assertThrows(SAXParseException.class, () -> XmlUtil.parseReader(new StringReader(SESSION_WITH_DOCTYPE)));
	}

	@Test
	public void sessionVersion() throws Exception {
		assertEquals("2", SessionLoader.getSessionVersion(toStream(SESSION)));
	}

	@Test
	public void sessionVersionDoctype() {
		assertThrows(SAXParseException.class, () -> SessionLoader.getSessionVersion(toStream(SESSION_WITH_DOCTYPE)));
	}

	@Test
	public void parseXml() throws Exception {
		assertNotNull(SessionLoaderImpl2.parseXml(toStream(SESSION)));
	}

	@Test
	public void parseXmlDoctype() {
		assertThrows(JAXBException.class, () -> SessionLoaderImpl2.parseXml(toStream(SESSION_WITH_DOCTYPE)));
	}
}
