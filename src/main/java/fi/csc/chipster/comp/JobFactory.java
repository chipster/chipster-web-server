package fi.csc.chipster.comp;

import fi.csc.chipster.toolbox.ToolboxTool;

public interface JobFactory {

	/**
	 * Creates job object using the tool description and job message, i.e., instantiates the comp
	 * job described by the description and parameterised by the parameters contained in job message. 
	 * @param config 
	 */
	public CompJob createCompJob(GenericJobMessage message, ToolboxTool tool, ResultCallback resultHandler,
			int jobTimeout) throws CompException;

	/**
	 * Returns true if the handler is unable to create jobs. Handler is still able to 
	 * create descriptions.
	 * 
	 * @return
	 */
	public boolean isDisabled();
}
