package fi.csc.chipster.sessionworker.xml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import fi.csc.chipster.util.XmlUtil;

public class SessionLoader {

	public static String getSessionVersion(InputStream inputStream)
			throws SAXException, IOException, ParserConfigurationException {
		try (InputStreamReader metadataReader = new InputStreamReader(inputStream)) {
			Document doc = XmlUtil.parseReader(metadataReader);
			return doc.getDocumentElement().getAttribute("format-version");
		}
	}
}
