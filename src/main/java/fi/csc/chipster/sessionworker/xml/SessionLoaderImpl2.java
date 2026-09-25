package fi.csc.chipster.sessionworker.xml;

import java.io.InputStream;
import java.util.zip.ZipException;

import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import fi.csc.chipster.sessionworker.xml.schema2.SessionType;

public class SessionLoaderImpl2 {
	/**
	 * Logger for this class
	 */
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	public static SessionType parseXml(InputStream metadataStream) throws JAXBException, SAXException, ZipException {
		if (metadataStream == null) {
			throw new ZipException(
					"session file corrupted, entry " + UserSession.SESSION_DATA_FILENAME + " was missing");
		}

		// parse the metadata xml to java objects using jaxb
		Unmarshaller unmarshaller = UserSession.getJAXBContext().createUnmarshaller();
		unmarshaller.setSchema(UserSession.getSchema());
		NonStoppingValidationEventHandler validationEventHandler = new NonStoppingValidationEventHandler();
		unmarshaller.setEventHandler(validationEventHandler);
		SAXSource source = new SAXSource(newXMLReader(), new InputSource(metadataStream));
		SessionType sessionType = unmarshaller.unmarshal(source, SessionType.class).getValue();

		if (validationEventHandler.hasEvents()) {
			throw new JAXBException("Invalid session file:\n" + validationEventHandler.getValidationEventsAsString());
		}

		return sessionType;
	}

	/**
	 * Create an XMLReader for the untrusted session metadata
	 * 
	 * Don't let JAXB create its own parser, so that DOCTYPE and external entities
	 * are rejected (XXE).
	 */
	private static XMLReader newXMLReader() throws SAXException {
		try {
			SAXParserFactory spf = SAXParserFactory.newInstance();
			spf.setNamespaceAware(true);
			spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
			spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			spf.setXIncludeAware(false);

			XMLReader reader = spf.newSAXParser().getXMLReader();
			reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			return reader;
		} catch (ParserConfigurationException e) {
			throw new SAXException("failed to configure the XML parser", e);
		}
	}
}
