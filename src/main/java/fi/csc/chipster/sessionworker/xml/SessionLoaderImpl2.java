package fi.csc.chipster.sessionworker.xml;

import java.io.InputStream;
import java.util.zip.ZipException;

import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xml.sax.SAXException;

import fi.csc.chipster.sessionworker.xml.schema2.SessionType;

public class SessionLoaderImpl2 {
	/**
	 * Logger for this class
	 */
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	public static SessionType parseXml(InputStream metadataStream) throws JAXBException, SAXException, ZipException {
		if (metadataStream == null) {
			throw new ZipException("session file corrupted, entry " + UserSession.SESSION_DATA_FILENAME + " was missing");
		}
		
		// parse the metadata xml to java objects using jaxb
		Unmarshaller unmarshaller = UserSession.getJAXBContext().createUnmarshaller();
		unmarshaller.setSchema(UserSession.getSchema());
		NonStoppingValidationEventHandler validationEventHandler = new NonStoppingValidationEventHandler();
		unmarshaller.setEventHandler(validationEventHandler);
		SessionType sessionType = unmarshaller.unmarshal(new StreamSource(metadataStream), SessionType.class).getValue();
		
		if (validationEventHandler.hasEvents()) {
			throw new JAXBException("Invalid session file:\n" + validationEventHandler.getValidationEventsAsString());
		}
		
		return sessionType;
	}
}
