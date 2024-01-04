package fi.csc.chipster.tools.ngs.regions;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import fi.csc.chipster.comp.Exceptions;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.comp.java.JavaCompJobBase;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.tools.model.Feature;
import fi.csc.chipster.tools.parsers.RegionOperations;
import fi.csc.chipster.util.IOUtils;

public abstract class RegionTool extends JavaCompJobBase {

	protected abstract LinkedList<Feature> operate(LinkedList<List<Feature>> inputs, LinkedHashMap<String, Parameter> parameters) throws Exception;
	
	@Override
	protected void execute() { 
		try {
			updateState(JobState.RUNNING, "preprocessing");

			// Parse inputs
			RegionOperations tool = new RegionOperations();
			LinkedList<List<Feature>> inputs = new LinkedList<>();
			for (int i = 0; i < toolDescription.getInputFiles().size(); i++) {
				File inputFile = new File(jobDataDir, toolDescription.getInputFiles().get(i).getFileName().getID());
				inputs.add(tool.loadFile(inputFile));
			}

			// Delegate actual processing to subclasses
			LinkedHashMap<String, Parameter> parameters = inputMessage.getParameters(JAVA_PARAMETER_SECURITY_POLICY, toolDescription);
			LinkedList<Feature> output = operate(inputs, parameters);
			
			// Sort result
			new RegionOperations().sort(output);
			
			// Write output
			FileOutputStream outputStream = null;
			try {
				outputStream = new FileOutputStream(new File(jobDataDir, toolDescription.getOutputFiles().get(0).getFileName().getID())); 
				tool.print(output, outputStream);

			} finally {
				IOUtils.closeIfPossible(outputStream);
			}
			
		} catch (Exception e) {
			this.setOutputText(Exceptions.getStackTrace(e));
			updateState(JobState.FAILED, e.getMessage());
			return;
		}
		updateState(JobState.RUNNING, "preprocessing finished");
	}

}
