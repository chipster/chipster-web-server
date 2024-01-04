package fi.csc.chipster.tools.ngs.regions;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.tools.model.Feature;
import fi.csc.chipster.tools.parsers.RegionOperations;

public class CombineRegionsTool extends RegionTool {

	@Override
	public String getSADL() {
		return 	"TOOL fi.csc.chipster.tools.ngs.regions.CombineRegionsTool.java: \"Combine region files\" (Returns combined regions from both input files. Also known as union.)" + "\n" +
				"INPUT data1.bed: \"Region file A\" TYPE GENERIC" + "\n" +
				"INPUT data2.bed: \"Region file B\" TYPE GENERIC" + "\n" +
				"OUTPUT combined.bed: \"Combined regions\"" + "\n" + 
				"PARAMETER merge.overlapping: \"Merge overlapping regions before returning them\" TYPE [yes: \"Yes\", no: \"No\"] DEFAULT yes (Should result be flattened?)" +
				"PARAMETER min.overlap.bp: \"Minimum number of overlapping bases, if merging\" TYPE INTEGER FROM 1 DEFAULT 1 (If result is flattened, how many bases are required to consider regions overlapping?)";
	}

	@Override
	protected LinkedList<Feature> operate(LinkedList<List<Feature>> inputs, LinkedHashMap<String, Parameter> parameters) {
		RegionOperations tool = new RegionOperations();
		boolean flatten = "yes".equals(parameters.get("merge.overlapping").getValue());
		Long minOverlap = Long.valueOf(parameters.get("min.overlap.bp").getValue());
		return tool.merge(inputs.get(0), inputs.get(1), minOverlap, flatten);
	}
}
