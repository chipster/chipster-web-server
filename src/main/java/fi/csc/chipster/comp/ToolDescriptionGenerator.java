package fi.csc.chipster.comp;

import fi.csc.chipster.toolbox.sadl.SADLDescription;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Input;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Output;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter;

/**
 *  
 * Generator for ToolDescription objects. ToolDescription objects are
 * compute service specific versions of analysis tools descriptions.
 * 
 * @author Aleksi Kallio
 *
 */
public class ToolDescriptionGenerator {

	/**
	 * Converts generic SADLDescription to ToolDescription.
	 * 
	 * @return ToolDescription
	 */	
	public ToolDescription generate(SADLDescription source) {
		ToolDescription description = new ToolDescription();
		
		description.setID(source.getName().getID());
		description.setDisplayName(source.getName().getDisplayName());
		description.setComment(source.getDescription());
		description.setSlotCount(source.getSlotCount());

		// not interested in inputs, they were figured out when job was submitted
		// I'm interested in inputs in java jobs
		for (Input input : source.getInputs()) {
			description.addInputFile(input.getName(), input.isOptional());
		}
		
		for (Output output : source.getOutputs()) {
			description.addOutputFile(output.getName(), output.isOptional(), output.isMeta());
		}
		
		for (Parameter parameter : source.getParameters()) {
			description.addParameter(parameter);
		}
		
		return description;
	}
}