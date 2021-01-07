package fi.csc.chipster.sessionworker.xml;

import javax.xml.XMLConstants;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xml.sax.SAXException;

/**
 * Utility class for working with user session files and
 * metadata files contained in them (session.xml). Metadata files
 * are specified by the XSD definition and accessed using
 * classes generated from the definition. To generate the classes,
 * use the Ant tasks in build.xml. 
 * 
 * @author Taavi Hupponen, Aleksi Kallio
 *
 */
public class UserSession {

	public static final String SESSION_DATA_FILENAME = "session.xml";
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
    
	public static JAXBContext getJAXBContext() throws JAXBException {
		return JAXBContext.newInstance("fi.csc.chipster.sessionworker.xml.schema2");
	}

	public static Schema getSchema() throws SAXException {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		return factory.newSchema(new StreamSource(UserSession.class.getResourceAsStream("session2.xsd")));
	}
}
