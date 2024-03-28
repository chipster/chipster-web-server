package fi.csc.chipster.tools.parsers;

import fi.csc.chipster.tools.model.Exon;
import fi.csc.chipster.tools.model.Region;

public class GtfToFeatureConversion {

	public static Exon parseLine(GtfLineParser parser, String line) {
		if (!parser.setLine(line)) {
			// header line
			return null;
		}

		Region region = parser.getRegion();
		String feature = parser.getFeature();
		String geneId = parser.getGeneId();
		String transcId = parser.getTranscriptId();

		String exonString = parser.getAttribute("exon_number");
		int exonNumber = -1;
		if (exonString != null) {
			exonNumber = Integer.valueOf(exonString);
		}
		String geneName = parser.getAttribute("gene_name");
		String transcName = parser.getAttribute("transcript_name");
		String biotype = null;

		Exon exon = null;

		// Standard gtf data (for example Ensembl)
		if ("exon".equals(feature) || "CDS".equals(feature)) {

			exon = new Exon(region, feature, exonNumber, geneId, transcId, geneName, transcName, biotype);

			// Custom almost-gtf data
		} else if (feature.startsWith("GenBank")) {

			if (geneId == null || transcId == null) {
				return null;
			}

			if ("GenBank gene".equals(feature)) {
				feature = "exon";
			} else if ("GenBank CDS".equals(feature)) {
				feature = "CDS";
			} else {
				geneId = feature + geneId;
				transcId = feature + transcId;

				if (geneName != null) {
					geneName = feature + " " + geneName;
				}

				if (transcName != null) {
					transcName = feature + " " + transcName;
				}

				feature = "exon";
			}

			exonNumber = 1;

			exon = new Exon(region, feature, exonNumber, geneId, transcId, geneName, transcName, biotype);
		}
		return exon;
	}
}
