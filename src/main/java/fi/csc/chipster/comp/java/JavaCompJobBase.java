package fi.csc.chipster.comp.java;

import fi.csc.chipster.comp.JobCancelledException;
import fi.csc.chipster.comp.OnDiskCompJobBase;
import fi.csc.chipster.comp.ParameterSecurityPolicy;
import fi.csc.chipster.comp.ToolDescription;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter;

public abstract class JavaCompJobBase extends OnDiskCompJobBase {

	public static class JavaParameterSecurityPolicy extends ParameterSecurityPolicy {

		private static final int MAX_VALUE_LENGTH = 10000;

		@Override
		public boolean isValueValid(String value, Parameter parameterDescription) {

			// No need to check content, parameters are passed inside Java Strings
			// Check only the parameter size (DOS protection)
			return value.length() <= MAX_VALUE_LENGTH;
		}

		@Override
		public boolean allowUncheckedParameters(ToolDescription toolDescription) {
			return "fi.csc.chipster.tools.common.DownloadFile.java".equals(toolDescription.getID());
		}

	}

	public static JavaParameterSecurityPolicy JAVA_PARAMETER_SECURITY_POLICY = new JavaParameterSecurityPolicy();

	@Override
	protected void preExecute() throws JobCancelledException {
		super.preExecute();
	}

	@Override
	protected void postExecute() throws JobCancelledException {
		super.postExecute();
	}

	@Override
	protected void cleanUp() {
		super.cleanUp();
	}

	@Override
	protected void cancelRequested() {
		// ignore by default
	}

	public abstract String getSADL();
}
