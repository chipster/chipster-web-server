package fi.csc.chipster.tools.ngs;

import java.io.File;

import fi.csc.chipster.comp.Exceptions;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.comp.java.JavaCompJobBase;
import fi.csc.chipster.tools.parsers.GtfLineParser;
import fi.csc.chipster.tools.parsers.TsvSorter;

public class SortGtf extends JavaCompJobBase {
	
	@Override
	public String getSADL() {
		return 	"TOOL fi.csc.chipster.tools.ngs.SortGtf.java: \"Sort GTF\" (Sort a GTF file by chromosome and start position.)" + "\n" +
				"INPUT unsorted.gtf: \"GTF file\" TYPE GENERIC" + "\n" +
				"OUTPUT sorted.gtf: \"Sorted GTF file\"" + "\n"; 
	}
	
	@Override
	protected void execute() { 
		updateState(JobState.RUNNING, "sorting");


		try {
			// files
			File inputFile = new File(jobDataDir, toolDescription.getInputFiles().get(0).getFileName().getID()); 
			File outputFile = new File(jobDataDir, toolDescription.getOutputFiles().get(0).getFileName().getID()); 

			// run sort
			sort(inputFile, outputFile);
						
		} catch (Exception e) {
			getResultMessage().setErrorMessage(Exceptions.getStackTrace(e));
			updateState(JobState.FAILED, "");
			return;
		}

		updateState(JobState.RUNNING, "sorting finished");
	}
	
	private static void sort(File inputFile, File outputFile) throws Exception {
		new TsvSorter().sort(
				inputFile, outputFile,
				GtfLineParser.Column.SEQNAME.ordinal(), 
				GtfLineParser.Column.START.ordinal(), new GtfLineParser());
	}

	public static void main(String[] args) throws Exception {

		try {
		
		File in = new File(args[0]);
		File out = new File(args[1]);
		
		sort(in, out);
		
		} catch (Exception e) {
			e.printStackTrace();
						
			System.out.println(
					"usage: \n" +
					"  SortGtf <file-in> <file-out>\n" +
					"example:\n " +
					"  java -cp chipster-2.9.10.jar fi.csc.chipster.tools.ngs.SortGtf Homo_sapiens.GRCh37.70.gtf Homo_sapiens.GRCh37.70-sort.gtf");
		}				
	}
}
