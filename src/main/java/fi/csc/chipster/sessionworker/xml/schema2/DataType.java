//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2016.03.18 at 08:34:46 AM EET 
//

package fi.csc.chipster.sessionworker.xml.schema2;

import java.util.ArrayList;
import java.util.List;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlSchemaType;
import jakarta.xml.bind.annotation.XmlType;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * <p>
 * Java class for dataType complex type.
 * 
 * <p>
 * The following schema fragment specifies the expected content contained within
 * this class.
 * 
 * <pre>
 * &lt;complexType name="dataType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="name" type="{http://www.w3.org/2001/XMLSchema}string"/>
 *         &lt;element name="id" type="{http://www.w3.org/2001/XMLSchema}string"/>
 *         &lt;element name="dataId" type="{http://www.w3.org/2001/XMLSchema}string"/>
 *         &lt;element name="folder" type="{http://www.w3.org/2001/XMLSchema}string" minOccurs="0"/>
 *         &lt;element name="result-of" type="{http://www.w3.org/2001/XMLSchema}string" minOccurs="0"/>
 *         &lt;element name="location" type="{}locationType" maxOccurs="unbounded" minOccurs="0"/>
 *         &lt;element name="link" type="{}linkType" maxOccurs="unbounded" minOccurs="0"/>
 *         &lt;element name="notes" type="{http://www.w3.org/2001/XMLSchema}string" minOccurs="0"/>
 *         &lt;element name="toolVersions" type="{http://www.w3.org/2001/XMLSchema}string" minOccurs="0"/>
 *         &lt;element name="creationTime" type="{http://www.w3.org/2001/XMLSchema}dateTime" minOccurs="0"/>
 *         &lt;element name="size" type="{http://www.w3.org/2001/XMLSchema}long" minOccurs="0"/>
 *         &lt;element name="checksum" type="{http://www.w3.org/2001/XMLSchema}string" minOccurs="0"/>
 *         &lt;element name="layout-x" type="{http://www.w3.org/2001/XMLSchema}int" minOccurs="0"/>
 *         &lt;element name="layout-y" type="{http://www.w3.org/2001/XMLSchema}int" minOccurs="0"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "dataType", propOrder = {
        "name",
        "id",
        "dataId",
        "folder",
        "resultOf",
        "location",
        "link",
        "notes",
        "toolVersions",
        "creationTime",
        "size",
        "checksum",
        "layoutX",
        "layoutY"
})
public class DataType {

    @XmlElement(required = true)
    protected String name;
    @XmlElement(required = true)
    protected String id;
    @XmlElement(required = true)
    protected String dataId;
    protected String folder;
    @XmlElement(name = "result-of")
    protected String resultOf;
    protected List<LocationType> location;
    protected List<LinkType> link;
    protected String notes;
    protected String toolVersions;
    @XmlSchemaType(name = "dateTime")
    protected XMLGregorianCalendar creationTime;
    protected Long size;
    protected String checksum;
    @XmlElement(name = "layout-x")
    protected Integer layoutX;
    @XmlElement(name = "layout-y")
    protected Integer layoutY;

    /**
     * Gets the value of the name property.
     * 
     * @return
     *         possible object is
     *         {@link String }
     * 
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the value of the name property.
     * 
     * @param value
     *              allowed object is
     *              {@link String }
     * 
     */
    public void setName(String value) {
        this.name = value;
    }

    /**
     * Gets the value of the id property.
     * 
     * @return
     *         possible object is
     *         {@link String }
     * 
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the value of the id property.
     * 
     * @param value
     *              allowed object is
     *              {@link String }
     * 
     */
    public void setId(String value) {
        this.id = value;
    }

    /**
     * Gets the value of the dataId property.
     * 
     * @return
     *         possible object is
     *         {@link String }
     * 
     */
    public String getDataId() {
        return dataId;
    }

    /**
     * Sets the value of the dataId property.
     * 
     * @param value
     *              allowed object is
     *              {@link String }
     * 
     */
    public void setDataId(String value) {
        this.dataId = value;
    }

    /**
     * Gets the value of the folder property.
     * 
     * @return
     *         possible object is
     *         {@link String }
     * 
     */
    public String getFolder() {
        return folder;
    }

    /**
     * Sets the value of the folder property.
     * 
     * @param value
     *              allowed object is
     *              {@link String }
     * 
     */
    public void setFolder(String value) {
        this.folder = value;
    }

    /**
     * Gets the value of the resultOf property.
     * 
     * @return
     *         possible object is
     *         {@link String }
     * 
     */
    public String getResultOf() {
        return resultOf;
    }

    /**
     * Sets the value of the resultOf property.
     * 
     * @param value
     *              allowed object is
     *              {@link String }
     * 
     */
    public void setResultOf(String value) {
        this.resultOf = value;
    }

    /**
     * Gets the value of the location property.
     * 
     * <p>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the location property.
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * 
     * <pre>
     * getLocation().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link LocationType }
     * 
     * 
     */
    public List<LocationType> getLocation() {
        if (location == null) {
            location = new ArrayList<LocationType>();
        }
        return this.location;
    }

    /**
     * Gets the value of the link property.
     * 
     * <p>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the link property.
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * 
     * <pre>
     * getLink().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link LinkType }
     * 
     * 
     */
    public List<LinkType> getLink() {
        if (link == null) {
            link = new ArrayList<LinkType>();
        }
        return this.link;
    }

    /**
     * Gets the value of the notes property.
     * 
     * @return
     *         possible object is
     *         {@link String }
     * 
     */
    public String getNotes() {
        return notes;
    }

    /**
     * Sets the value of the notes property.
     * 
     * @param value
     *              allowed object is
     *              {@link String }
     * 
     */
    public void setNotes(String value) {
        this.notes = value;
    }

    /**
     * Gets the value of the toolVersions property.
     * 
     * @return
     *         possible object is
     *         {@link String }
     * 
     */
    public String getToolVersions() {
        return toolVersions;
    }

    /**
     * Sets the value of the toolVersions property.
     * 
     * @param value
     *              allowed object is
     *              {@link String }
     * 
     */
    public void setToolVersions(String value) {
        this.toolVersions = value;
    }

    /**
     * Gets the value of the creationTime property.
     * 
     * @return
     *         possible object is
     *         {@link XMLGregorianCalendar }
     * 
     */
    public XMLGregorianCalendar getCreationTime() {
        return creationTime;
    }

    /**
     * Sets the value of the creationTime property.
     * 
     * @param value
     *              allowed object is
     *              {@link XMLGregorianCalendar }
     * 
     */
    public void setCreationTime(XMLGregorianCalendar value) {
        this.creationTime = value;
    }

    /**
     * Gets the value of the size property.
     * 
     * @return
     *         possible object is
     *         {@link Long }
     * 
     */
    public Long getSize() {
        return size;
    }

    /**
     * Sets the value of the size property.
     * 
     * @param value
     *              allowed object is
     *              {@link Long }
     * 
     */
    public void setSize(Long value) {
        this.size = value;
    }

    /**
     * Gets the value of the checksum property.
     * 
     * @return
     *         possible object is
     *         {@link String }
     * 
     */
    public String getChecksum() {
        return checksum;
    }

    /**
     * Sets the value of the checksum property.
     * 
     * @param value
     *              allowed object is
     *              {@link String }
     * 
     */
    public void setChecksum(String value) {
        this.checksum = value;
    }

    /**
     * Gets the value of the layoutX property.
     * 
     * @return
     *         possible object is
     *         {@link Integer }
     * 
     */
    public Integer getLayoutX() {
        return layoutX;
    }

    /**
     * Sets the value of the layoutX property.
     * 
     * @param value
     *              allowed object is
     *              {@link Integer }
     * 
     */
    public void setLayoutX(Integer value) {
        this.layoutX = value;
    }

    /**
     * Gets the value of the layoutY property.
     * 
     * @return
     *         possible object is
     *         {@link Integer }
     * 
     */
    public Integer getLayoutY() {
        return layoutY;
    }

    /**
     * Sets the value of the layoutY property.
     * 
     * @param value
     *              allowed object is
     *              {@link Integer }
     * 
     */
    public void setLayoutY(Integer value) {
        this.layoutY = value;
    }

}
