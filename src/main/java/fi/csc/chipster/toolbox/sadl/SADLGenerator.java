package fi.csc.chipster.toolbox.sadl;

import java.util.List;

import fi.csc.chipster.toolbox.sadl.SADLDescription.Entity;
import fi.csc.chipster.toolbox.sadl.SADLDescription.IOEntity;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Input;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Name;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Output;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter;
import fi.csc.chipster.toolbox.sadl.SADLSyntax.ParameterType;

/**
 * Generates SADL source code from parsed objects.
 * 
 * @author Aleksi Kallio
 *
 */
public class SADLGenerator {

	/**
	 * Creates a SADL source code representation of parsed syntax object
	 * (SADLDescription).
	 * Due to whitespace etc. the returned code might not be identical to the
	 * original
	 * source. However if the returned String is used to create a new parsed syntax,
	 * it
	 * should return the exactly same string.
	 * 
	 * @see SADLDescription
	 * 
	 * @return SADL source representation
	 */
	public static String generate(SADLDescription sadl) {

		String string = "TOOL " + generateName(sadl.getName()) + " (" + escapeIfNeeded(sadl.getDescription()) + ")\n";

		string += generateInputs("INPUT", sadl.getInputs());

		string += generateOutputs("OUTPUT", sadl.getOutputs());

		if (!sadl.getParameters().isEmpty()) {
			for (Parameter parameter : sadl.getParameters()) {
				String paramString = "PARAMETER " + generateOptional(parameter) + parameter.getName() + " TYPE ";

				if (parameter.getType() == ParameterType.ENUM) {
					paramString += "[";
					boolean first = true;
					for (Name option : parameter.getSelectionOptions()) {
						if (!first) {
							paramString += ", ";
						} else {
							first = false;
						}
						paramString += option;
					}
					paramString += "] ";

				} else {
					paramString += parameter.getType() + " ";
				}

				if (parameter.getFrom() != null) {
					paramString += "FROM " + parameter.getFrom() + " ";
				}

				if (parameter.getTo() != null) {
					paramString += "TO " + parameter.getTo() + " ";
				}

				if (parameter.getDefaultValues().length > 0) {
					paramString += "DEFAULT ";
					boolean first = true;
					for (String defaultValue : parameter.getDefaultValues()) {
						paramString += first ? "" : ",";
						paramString += quoteIfNeeded(defaultValue) + " ";
						first = false;
					}
				}

				paramString += possibleComment(parameter.getDescription());

				string += paramString + "\n";
			}
		}

		if (sadl.getRuntime() != null && !sadl.getRuntime().isEmpty()) {
			string += SADLSyntax.KEYWORD_RUNTIME + " " + sadl.getRuntime() + "\n";
		}

		if (sadl.getSlotCount() != null) {
			string += SADLSyntax.KEYWORD_SLOTS + " " + sadl.getSlotCount() + "\n";
		}

		return string;
	}

	private static String possibleComment(String comment) {
		if (comment != null) {
			return "(" + escapeIfNeeded(comment) + ")";
		} else {
			return "";
		}
	}

	private static String generateOutputs(String header, List<Output> outputList) {
		String string = "";
		if (!outputList.isEmpty()) {
			for (Output output : outputList) {
				string += header + " " + generateExtensions(output) + generateName(output.getName()) + " "
						+ possibleComment(output.getDescription()) + "\n";
			}
		}
		return string;
	}

	private static String generateInputs(String header, List<Input> inputList) {
		String string = "";
		if (!inputList.isEmpty()) {
			for (Input input : inputList) {
				string += header + " " + generateExtensions(input) + generateName(input.getName()) + " TYPE "
						+ input.getType().getName() + " " + possibleComment(input.getDescription()) + "\n";
			}

		}
		return string;
	}

	private static String generateExtensions(IOEntity entity) {
		return (entity.isMeta() ? "META " : "") + generateOptional(entity);
	}

	private static String generateOptional(Entity entity) {
		return (entity.isOptional() ? "OPTIONAL " : "");
	}

	public static String generateName(Name name) {

		String firstPart;
		if (name.isNameSet()) {
			firstPart = name.getPrefix() + "{...}" + name.getPostfix();
		} else {
			firstPart = name.getID();
		}

		String secondPart;
		if (name.getDisplayName() != null) {
			secondPart = ": " + quoteIfNeeded(name.getDisplayName());

		} else {
			secondPart = "";
		}

		return quoteIfNeeded(firstPart) + secondPart;
	}

	private static String quoteIfNeeded(String string) {
		if (string.isEmpty() || string.contains(" ")
				|| SADLGenerator.containsAnyOf(string, true, SADLTokeniser.tokenEndingOperators())) {
			return "\"" + escapeIfNeeded(string) + "\"";
		} else {
			return string;
		}
	}

	public static boolean containsAnyOf(String toCompare, boolean caseSensitive, String... strings) {
		if (!caseSensitive) {
			toCompare = toCompare.toLowerCase();
		}
		for (String string : strings) {
			if (!caseSensitive) {
				string = string.toLowerCase();
			}
			if (toCompare.contains(string)) {
				return true;
			}

		}
		return false;
	}

	private static String escapeIfNeeded(String string) {
		if (string != null) {
			for (String operator : SADLTokeniser.blockEndingOperators()) {
				string = string.replace(operator, SADLSyntax.ESCAPE + operator);
			}

			return string;
		}
		return null;
	}
}
