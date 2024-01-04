/*
 * Created on Feb 24, 2005
 *
 */
package fi.csc.chipster.comp;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import fi.csc.chipster.toolbox.sadl.SADLDescription.Name;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter;


/**
 * Compute service specific versions of tool description.
 * Content is overlapping with generic SADLDescription objects, but 
 * some features are not needed here and excluded. 
 */
public class ToolDescription {

	
	/**
	 * Describes an output (parameter name and file name). 
	 */
	public static class OutputDescription {
        private final Name fileName;
        private final boolean optional;
        private final boolean meta;

        public Name getFileName() {
            return fileName;
        }
        
        public boolean isOptional() {
            return optional;
        }

        public boolean isMeta() {
            return meta;
        }

	    public OutputDescription(Name fileName, boolean optional, boolean meta) {
	        this.fileName = fileName;
	        this.optional = optional;
	        this.meta = meta;
	    }
	}
	

	/**
	 * Describes an input (parameter name and file name). 
	 */
	public static class InputDescription {
        private final Name fileName;
        private final boolean optional;

        public Name getFileName() {
            return fileName;
        }
	    
	    public InputDescription(Name fileName, boolean optional) {
	        this.fileName = fileName;
	        this.optional = optional;
	    }

        public boolean isOptional() {
            return optional;
        }
	}

	
	private String id;

	/**
	 * Actual executable that handles the analysis.
	 */
	private String command;
	
	/**
	 * The actual content of the operation implementation.
	 */
	private Object implementation;
	
	/**
	 * Analysis name (used in GUI etc.)
	 */
	private String displayName;
	
	/**
	 * Description.
	 */
	private String comment;

	

	private final List<InputDescription> inputFiles = new LinkedList<>();
	private final List<OutputDescription> outputFiles = new LinkedList<>();
	private final LinkedHashMap<String, Parameter> parameters = new LinkedHashMap<>();
	private String sourceCode;
	private String helpURL = null;

	private String initialiser;

	private Integer slotCount;
	
	public String getCommand() {
		return command;
	}
	
	public Object getImplementation() {
		return implementation;
	}

	public List<InputDescription> getInputFiles() {
		return inputFiles;
	}
	
	public List<OutputDescription> getOutputFiles() {
		return outputFiles;
	}
	
	public LinkedHashMap<String, Parameter> getParameters() {
		return parameters;
	}
	
	public void addParameter(fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter parameter) {
		parameters.put(parameter.getName().getID(), parameter);
	}
	
	public String getInitialiser() {
		return this.initialiser;
	}

	public void setInitialiser(String initialiser) {
		this.initialiser = initialiser;
	}

	public void setCommand(String command) {
		this.command = command;
	}

	public void setImplementation(Object implementation) {
		this.implementation = implementation;
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	public String getID() {
		 return this.id;
	}
	
	public String getDisplayName() {
		if (displayName == null) {
			return id;
		} else {
			return displayName;
		}
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}
	

	public void addInputFile(Name fileName, boolean optional) {
		inputFiles.add(new InputDescription(fileName, optional));
	}
	
	public void addOutputFile(Name fileName, boolean optional, boolean meta) {
		outputFiles.add(new OutputDescription(fileName, optional, meta));
	}

	public void setSourceCode(String sourceCode) {
		this.sourceCode = sourceCode;		
	}

	public String getSourceCode() {
		return sourceCode;
	}

	public void setHelpURL(String helpURL) {
	    this.helpURL = helpURL;
	}

    public String getHelpURL() {
	    return helpURL;
	}
	
	public void setID(String id) {
		this.id = id;
	}

	public Integer getSlotCount() {
		if (this.slotCount == null) {
			return 1;
		} else {
			return this.slotCount;
		}
	}

	public void setSlotCount(Integer slotCount) {
		this.slotCount = slotCount;
	}
}
 