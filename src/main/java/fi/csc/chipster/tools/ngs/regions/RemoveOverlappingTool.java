package fi.csc.chipster.tools.ngs.regions;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.tools.model.Feature;
import fi.csc.chipster.tools.parsers.RegionOperations;

public class RemoveOverlappingTool extends RegionTool {

	@Override
	public String getSADL() {
		return 	"TOOL fi.csc.chipster.tools.ngs.regions.RemoveOverlappingTool.java: \"Remove overlapping regions\" (Returns regions from file A which do not overlap with any of the regions in file B. Also known as subtraction.)" + "\n" +
				"INPUT data1.bed: \"Region file A\" TYPE GENERIC" + "\n" +
				"INPUT data2.bed: \"Region file B\" TYPE GENERIC" + "\n" +
				"OUTPUT nonoverlapping.bed: \"Regions of first input that do not overlap\"" + "\n" + 
				"PARAMETER min.overlap.bp: \"Minimum number of overlapping bases\" TYPE INTEGER FROM 1 DEFAULT 1 (How many bases are required to consider regions overlapping?)";
	}

	@Override
	protected LinkedList<Feature> operate(LinkedList<List<Feature>> inputs, LinkedHashMap<String, Parameter> parameters) {
		RegionOperations tool = new RegionOperations();
		Long minOverlap = Long.valueOf(parameters.get("min.overlap.bp").getValue());
		return tool.subtract(inputs.get(0), inputs.get(1), minOverlap);
	}
}
