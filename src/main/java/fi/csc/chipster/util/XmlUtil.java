package fi.csc.chipster.util;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 *
 * @author  Aleksi Kallio
 */
public class XmlUtil {
    
    public static Document parseReader(Reader reader) throws org.xml.sax.SAXException, IOException, ParserConfigurationException {
        return newDocumentBuilder().parse(new org.xml.sax.InputSource(reader));
    }
	
	/**
	 * Gets the child elements of a parent element. Unlike DOM's getElementsByTagName, this does no recursion,
	 * uses local name (namespace free) instead of tag name, result is a proper Java data structure and result
	 * needs no casting. In other words, this method does not suck unlike DOM.
	 * 
	 * @param parent the XML parent element
	 * @param name name of the child elements, if null then all are returned
	 */
	public static List<Element> getChildElements(Element parent, String name) {
		List<Element> childElements = new ArrayList<Element>();
		NodeList childNodes = parent.getChildNodes();
		
		for (int i = 0; i < childNodes.getLength(); i++) {
			// get elements
			if (childNodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
				
				// match element name
				Element childElement = (Element) childNodes.item(i);
				if (name == null || childElement.getLocalName().equals(name)) {
					childElements.add(childElement);
				}
			}
		}
		
		return childElements;
	}

	public static Element getChildElement(Element parent, String name) {
		return getChildElement(parent, name, false);
	}
	
	public static Element getChildElement(Element parent, String name, boolean strict) {
		List<Element> childElements = getChildElements(parent, name);
		if (strict && childElements.size() != 1) {
			throw new IllegalArgumentException("parent must contain exactly one element with the given name");
		} 
		
		return childElements.isEmpty() ? null : childElements.get(0);	
	}
	
    private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
    	// SAXParsers are not concurrency compatible, so always return a new instance to prevent thread issues 
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder();
    }
}
