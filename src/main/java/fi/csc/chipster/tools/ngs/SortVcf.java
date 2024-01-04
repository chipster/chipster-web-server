package fi.csc.chipster.tools.ngs;

import java.io.File;

import fi.csc.chipster.comp.Exceptions;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.comp.java.JavaCompJobBase;
import fi.csc.chipster.tools.parsers.TsvSorter;
import fi.csc.chipster.tools.parsers.VcfLineParser;

public class SortVcf extends JavaCompJobBase {
	
	@Override
	public String getSADL() {
		return 	"TOOL fi.csc.chipster.tools.ngs.SortVcf.java: \"Sort VCF\" (Sort a VCF file by chromosome and position.)" + "\n" +
				"INPUT unsorted.vcf: \"VCF file\" TYPE GENERIC" + "\n" +
				"OUTPUT sorted.vcf: \"Sorted VCF file\"" + "\n"; 

	}
	
	
	@Override
	protected void execute() { 
		updateState(JobState.RUNNING, "sorting");


		try {
			// files
			File inputFile = new File(jobDataDir, toolDescription.getInputFiles().get(0).getFileName().getID()); 
			File outputFile = new File(jobDataDir, toolDescription.getOutputFiles().get(0).getFileName().getID()); 

			// run sort
			new TsvSorter().sort(
					inputFile, outputFile, 
					VcfLineParser.Column.CHROM.ordinal(), 
					VcfLineParser.Column.POS.ordinal(), new VcfLineParser());

		} catch (Exception e) {
			getResultMessage().setErrorMessage(Exceptions.getStackTrace(e));
			updateState(JobState.FAILED, "");
			return;
		}

		updateState(JobState.RUNNING, "sorting finished");
	}
}

