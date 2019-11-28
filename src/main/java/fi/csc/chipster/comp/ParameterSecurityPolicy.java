package fi.csc.chipster.comp;

import fi.csc.chipster.comp.ToolDescription.ParameterDescription;

public abstract class ParameterSecurityPolicy {
		/**
		 * Checks that given value is valid from a security point of view. Comp jobs
		 * implement this to provide context dependent checking. Typically validity
		 * depends on the type of value (numeric, text...), so ParameterDescription is
		 * also passed.
		 * 
		 * @return true iff is valid
		 */
		public abstract boolean isValueValid(String value, ParameterDescription parameterDescription);

		/**
		 * Most comp jobs don't support UNCHECKED_STRING parameter
		 * 
		 * By overriding this method the subclass promises to handle this type safely.
		 * 
		 * ToolDescription used for limiting to certain tools
		 * 
		 * @see fi.csc.chipster.comp.python.PythonCompJob#transformVariable(ParameterDescription,
		 *      String)
		 * 
		 */
		public boolean allowUncheckedParameters(ToolDescription description) {
			return false;
		}
	}
