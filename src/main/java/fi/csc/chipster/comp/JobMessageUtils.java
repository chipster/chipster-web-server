package fi.csc.chipster.comp;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.toolbox.sadl.SADLSyntax.ParameterType;
public class JobMessageUtils {
	/**
	 * This should really be in the GenericJobMessage, but static methods in
	 * interfaces are only allowed starting from Java 1.8.
	 * 
	 * @param securityPolicy
	 * @param description
	 * @param parameters
	 * @return
	 * @throws ParameterValidityException
	 */
	public static LinkedHashMap<String, Parameter> checkParameterSafety(ParameterSecurityPolicy securityPolicy, ToolDescription description,
			LinkedHashMap<String, Parameter> parameters) throws ParameterValidityException {
		// Do argument checking first
		if (securityPolicy == null) {
			throw new IllegalArgumentException("security policy cannot be null");
		}
		if (description == null) {
			throw new IllegalArgumentException("tool description cannot be null");
		}

		// Count parameter descriptions
		int parameterDescriptionCount = description.getParameters().size();

		// Check that description and values match
		if (parameterDescriptionCount != parameters.size()) {
			throw new ParameterValidityException(
					"number of parameter descriptions (" + parameterDescriptionCount + ") does not match the number of parameter values (" + parameters.size() + ")");			
		}

		// Check if there are any disallowed characters in the parameter value 
		for (Parameter parameter : parameters.values()) {

			fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter toolParameter = description.getParameters().get(parameter.getParameterId());

			if (toolParameter == null) {
				// shouldn't happen, checked already in RestJobMessage.getParameters()
				throw new IllegalArgumentException("parameter not found from tool: " + parameter.getParameterId());
			}

			if (isChecked(toolParameter)) {
				if (!securityPolicy.isValueValid(parameter.getValue(), toolParameter)) {
					throw new ParameterValidityException(
							"illegal value for parameter " + parameter.getParameterId() + ": " + parameter.getValue());
				}
			} else {
				if (!securityPolicy.allowUncheckedParameters(description)) {
					throw new UnsupportedOperationException("unchecked parameters are not allowed");
				}
			}
		}
		
	    // Check that the selected enum option exists
		// Should we check also other parameter constraints like integer limits?

		for (Parameter parameter : parameters.values()) {
			fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter toolParameter = description.getParameters().get(parameter.getParameterId());

			if (toolParameter.getType() == ParameterType.ENUM) {
				Set<String> options = Stream.of(toolParameter.getSelectionOptions()).map(o -> o.getID()).collect(Collectors.toSet());
               
               if (!options.contains(parameter.getValue())) {
                   throw new ParameterValidityException(
                           "Enum parameter '" + parameter.getParameterId() + "' does not have option '" + parameter.getValue() + "'. Options: " + RestUtils.asJson(options) + ". ");
               }
			}
		}
				
		// Everything was ok, return the parameters
		return parameters;
	}

	public static boolean isChecked(fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter param) {
		return param.getType() != ParameterType.UNCHECKED_STRING;
	}
}
