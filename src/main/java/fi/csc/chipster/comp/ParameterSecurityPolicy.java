package fi.csc.chipster.comp;

import fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter;

public abstract class ParameterSecurityPolicy {
		/**
		 * Checks that given value is valid from a security point of view. Comp jobs
		 * implement this to provide context dependent checking. Typically validity
		 * depends on the type of value (numeric, text...), so ParameterDescription is
		 * also passed.
		 * 
     * @param value
     * @param parameterDescription
		 * @return true iff is valid
		 */
		public abstract boolean isValueValid(String value, Parameter parameterDescription);

		/**
		 * Most comp jobs don't support UNCHECKED_STRING parameter
		 * 
		 * By overriding this method the subclass promises to handle this type safely.
		 * 
		 * ToolDescription used for limiting to certain tools
		 * 
		 * @see fi.csc.chipster.comp.python.PythonCompJob#transformVariable(ParameterDescription,
		 *      String)
		 * @param description
		 * @return boolean
		 */
		public boolean allowUncheckedParameters(ToolDescription description) {
			return false;
		}
	}
