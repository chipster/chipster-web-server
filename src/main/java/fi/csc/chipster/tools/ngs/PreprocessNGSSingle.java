package fi.csc.chipster.tools.ngs;

import java.io.File;

import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.comp.java.JavaCompJobBase;
import fi.csc.chipster.tools.parsers.SamBamUtils;
import fi.csc.chipster.tools.parsers.SamBamUtils.SamBamUtilState;
import fi.csc.chipster.tools.parsers.SamBamUtils.SamBamUtilStateListener;

public class PreprocessNGSSingle extends JavaCompJobBase {

	@Override
	public String getSADL() {
		return 	"TOOL fi.csc.chipster.tools.ngs.PreprocessNGSSingle.java: \"Convert SAM to BAM, sort and index BAM\" (Converts SAM to BAM and sorts and indexes BAM files. Please note that this preprocessing is required for visualising the data in the Chipster Genome browser. This tools is based on the Picard package.)" + "\n" +
				"INPUT data.bam: \"Input bam file\" TYPE GENERIC" + "\n" +
				"OUTPUT preprocessed.bam: \"Preprocessed bam file\"" + "\n" + 
		        "OUTPUT preprocessed.bam.bai: \"Preprocessed bam index file\"" + "\n"; 
	}
	
	
	@Override
	protected void execute() { 
		updateState(JobState.RUNNING, "preprocessing");


		try {
			// files
			File inputFile = new File(jobDataDir, toolDescription.getInputFiles().get(0).getFileName().getID()); 
			File outputFile = new File(jobDataDir, toolDescription.getOutputFiles().get(0).getFileName().getID()); 
			File indexOutputFile = new File(jobDataDir, toolDescription.getOutputFiles().get(1).getFileName().getID());


			// run preprocessing
			SamBamUtils samBamUtil= new SamBamUtils(new SamBamUtilStateListener() {

				@Override
				public void stateChanged(SamBamUtilState newState) {
					// update detail state
					updateState(JobState.RUNNING, "preprocess: " + newState.getState());
				}

			});

			samBamUtil.preprocessSamBam(inputFile, outputFile, indexOutputFile);


		} catch (Exception e) {
			updateState(JobState.FAILED, e.getMessage());
			return;
		}

		updateState(JobState.RUNNING, "preprocessing finished");
	}
}
