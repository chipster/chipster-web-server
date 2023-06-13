package fi.csc.chipster.comp;

import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.chipster.toolbox.runtime.Runtime;

public interface JobFactory {

	/**
	 * Creates job object using the tool description and job message, i.e., instantiates the comp
	 * job described by the description and parameterised by the parameters contained in job message. 
	 * @param dbJob 
	 * @param runtime 
	 * @param config 
	 */
	public CompJob createCompJob(GenericJobMessage message, ToolboxTool tool, ResultCallback resultHandler,
			int jobTimeout, Job dbJob, Runtime runtime) throws CompException;

	/**
	 * Returns true if the handler is unable to create jobs. Handler is still able to 
	 * create descriptions.
	 * 
	 * @return
	 */
	public boolean isDisabled();
}
