package fi.csc.chipster.sessionworker.xml;

import java.io.InputStream;
import java.util.zip.ZipException;

import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;

import org.apache.log4j.Logger;
import org.xml.sax.SAXException;

import fi.csc.chipster.sessionworker.xml.schema2.SessionType;

public class SessionLoaderImpl2 {
	/**
	 * Logger for this class
	 */
	@SuppressWarnings("unused")
	private static final Logger logger = Logger.getLogger(SessionLoaderImpl2.class);

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
