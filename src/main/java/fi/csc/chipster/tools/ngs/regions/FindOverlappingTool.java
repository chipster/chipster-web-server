package fi.csc.chipster.tools.ngs.regions;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.tools.model.Feature;
import fi.csc.chipster.tools.parsers.RegionOperations;

public class FindOverlappingTool extends RegionTool {

	@Override
	public String getSADL() {
		return 	"TOOL fi.csc.chipster.tools.ngs.regions.FindOverlappingTool.java: \"Find overlapping regions\" (Returns regions that have overlap with some region in the other input file. Also known as intersection.)" + "\n" +
				"INPUT data1.bed: \"Region file A\" TYPE GENERIC" + "\n" +
				"INPUT data2.bed: \"Region file B\" TYPE GENERIC" + "\n" +
				"OUTPUT overlapping.bed: \"Overlapping regions\"" + "\n" + 
				"PARAMETER return.type: \"Type of returned regions\" TYPE [first: \"Original regions from file A\", first_augmented: \"Original regions from file A augmented with info from file B\", both: \"Original regions from both files\", merged: \"Regions from both files merged\", intersection: \"Overlapping parts of the regions\"] DEFAULT first (How overlapping regions are returned?)" + 
				"PARAMETER min.overlap.bp: \"Minimum number of overlapping bases\" TYPE INTEGER FROM 1 DEFAULT 1 (How many bases are required to consider regions overlapping?)";
	}
	

	@Override
	protected LinkedList<Feature> operate(LinkedList<List<Feature>> inputs, LinkedHashMap<String, Parameter> parameters) {
		RegionOperations tool = new RegionOperations();
		RegionOperations.PairPolicy pairPolicy;

		String returnType = parameters.get("return.type").getValue();
		String minOverlapBp = parameters.get("min.overlap.bp").getValue();

		if ("intersection".equals(returnType)) {
			pairPolicy = RegionOperations.INTERSECT_PAIR_POLICY; 
		} else if ("both".equals(returnType)) {
			pairPolicy = RegionOperations.ORIGINALS_PAIR_POLICY;
		} else if ("merged".equals(returnType)) {
			pairPolicy = RegionOperations.MERGE_PAIR_POLICY;
		} else if ("first_augmented".equals(returnType)) {
			pairPolicy = RegionOperations.LEFT_PAIR_POLICY_WITH_AUGMENTATION;
		} else {
			pairPolicy = RegionOperations.LEFT_PAIR_POLICY;
		}
		Long minOverlap = Long.valueOf(minOverlapBp);
		return tool.intersect(inputs.get(0), inputs.get(1), minOverlap, pairPolicy, false);
		
	}
}
