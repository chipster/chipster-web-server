package fi.csc.chipster.comp.java;

import fi.csc.chipster.comp.JobCancelledException;
import fi.csc.chipster.comp.JobState;

public class FailingCompJob extends JavaCompJobBase {

	@Override
	protected void execute() throws JobCancelledException {
		this.setErrorMessage("This job always fails.");
		this.setOutputText("There's no way around this.");
		updateState(JobState.FAILED);
	}


	@Override
	public String getSADL() {		
		return " ANALYSIS Test/FailJava (Java job which fails.) ";
	}

}
