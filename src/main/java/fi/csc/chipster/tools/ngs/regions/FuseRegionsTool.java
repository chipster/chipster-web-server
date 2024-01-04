package fi.csc.chipster.tools.ngs.regions;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.tools.model.Feature;
import fi.csc.chipster.tools.parsers.RegionOperations;

public class FuseRegionsTool extends RegionTool {

	@Override
	public String getSADL() {
		return 	"TOOL fi.csc.chipster.tools.ngs.regions.FuseRegionsTool.java: \"Fuse overlapping regions\" (Merges overlapping regions of a single file. The returned file does not have any internal overlapping.)" + "\n" +
				"INPUT data.bed: \"Regions to fuse\" TYPE GENERIC" + "\n" +
				"OUTPUT fused.bed: \"Merged regions\"" + "\n"; 
	}

	@Override
	protected LinkedList<Feature> operate(LinkedList<List<Feature>> inputs, LinkedHashMap<String, Parameter> parameters) {
		RegionOperations tool = new RegionOperations();
		return tool.flatten(inputs.get(0));
	}
}
