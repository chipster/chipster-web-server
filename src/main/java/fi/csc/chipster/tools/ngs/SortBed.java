package fi.csc.chipster.tools.ngs;

import java.io.File;

import fi.csc.chipster.comp.Exceptions;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.comp.java.JavaCompJobBase;
import fi.csc.chipster.tools.parsers.BedLineParser;
import fi.csc.chipster.tools.parsers.TsvSorter;

public class SortBed extends JavaCompJobBase {
	
	@Override
	public String getSADL() {
		return 	"TOOL fi.csc.chipster.tools.ngs.SortBed.java: \"Sort BED\" (Sort a BED file by chromosome and start position.)" + "\n" +
				"INPUT regions.bed: \"BED file\" TYPE GENERIC" + "\n" +
				"OUTPUT sorted.bed: \"Sorted BED file\"" + "\n"; 

	}
	
	
	@Override
	protected void execute() { 
		updateState(JobState.RUNNING, "sorting");


		try {
			// files
			File inputFile = new File(jobDataDir, toolDescription.getInputFiles().get(0).getFileName().getID()); 
			File outputFile = new File(jobDataDir, toolDescription.getOutputFiles().get(0).getFileName().getID()); 

			// run sort
			//BEDParser increments coordinates by one, but it's not a problem because only its column order is used
			new TsvSorter().sort(
					inputFile, outputFile,
					BedLineParser.Column.CHROM.ordinal(), BedLineParser.Column.CHROM_START.ordinal(), new BedLineParser(false));

		} catch (Exception e) {
			getResultMessage().setErrorMessage(Exceptions.getStackTrace(e));
			updateState(JobState.FAILED, "");
			return;
		}

		updateState(JobState.RUNNING, "sorting finished");
	}
}
