package fi.csc.chipster.toolbox;

import java.util.List;

import fi.csc.chipster.toolbox.sadl.SADLDescription;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Name;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter;
import fi.csc.chipster.toolbox.sadl.SADLParser;
import fi.csc.chipster.toolbox.sadl.SADLSyntax.ParameterType;

public class ChipsterSADLParser extends SADLParser {

	public ChipsterSADLParser() {
		this(null);
	}

	public ChipsterSADLParser(String filename) {
		super(filename);
		addInputType(ChipsterInputTypes.AFFY);
		addInputType(ChipsterInputTypes.CDNA);
		addInputType(ChipsterInputTypes.GENE_EXPRS);
		addInputType(ChipsterInputTypes.GENELIST);
		addInputType(ChipsterInputTypes.PHENODATA);
		addInputType(ChipsterInputTypes.BAM);
		addInputType(ChipsterInputTypes.FASTA);
		addInputType(ChipsterInputTypes.FASTQ);
		addInputType(ChipsterInputTypes.GTF);
		addInputType(ChipsterInputTypes.MOTHUR_OLIGOS);
		addInputType(ChipsterInputTypes.MOTHUR_NAMES);
		addInputType(ChipsterInputTypes.MOTHUR_GROUPS);
		addInputType(ChipsterInputTypes.MOTHUR_STABILITY);
		addInputType(ChipsterInputTypes.MOTHUR_COUNT);
		;
	}

	public static class Validator {

		public List<SADLDescription> validate(String filename, String sadl) throws ParseException {
			ChipsterSADLParser parser = new ChipsterSADLParser(filename);
			List<SADLDescription> descriptions = parser.parseMultiple(sadl);
			for (SADLDescription description : descriptions) {
				checkParsedContent(description);
			}
			return descriptions;
		}

		private void checkParsedContent(SADLDescription description) {
			for (Parameter parameter : description.getParameters()) {

				// ENUM
				if (parameter.getType() == ParameterType.ENUM) {
					// check that enum is not empty
					if (parameter.getSelectionOptions() == null || parameter.getSelectionOptions().length == 0) {
						throw new RuntimeException("enum parameter " + parameter.getName() + " has no options");
					}
					// check that enum default value is legal
					for (String defaultValue : parameter.getDefaultValues()) {
						boolean found = false;
						for (Name value : parameter.getSelectionOptions()) {
							if (defaultValue.equals(value.getID())) {
								found = true;
								break;
							}
						}
						if (!found) {
							throw new RuntimeException("enum parameter " + parameter.getName()
									+ " has undefined default value \"" + defaultValue + "\"");
						}
					}

				}

				// not ENUM
				else {
					// check that non-enum values do not have multiple default values
					if (parameter.getDefaultValues().length > 1) {
						throw new RuntimeException(
								"non-enum parameter " + parameter.getName() + " has multiple default values");
					}
				}
			}

		}
	};
}
