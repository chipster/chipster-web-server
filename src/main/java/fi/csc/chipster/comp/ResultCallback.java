/*
 * Created on Mar 4, 2005
 *
 */

package fi.csc.chipster.comp;

import java.io.File;

import fi.csc.chipster.filebroker.LegacyRestFileBrokerClient;
import fi.csc.chipster.toolbox.ToolboxClientComp;

/**
 * For reporting back analysis results. These methods are called by the thread
 * running a job.
 * 
 * @author hupponen
 */
public interface ResultCallback {

	/**
	 * All the data must be sent before returning, as after calling this method, the
	 * thread
	 * running the job will clean up job data files.
	 * 
	 * 
	 * @param inputMessage
	 * @param resultMessage
	 */
	public void sendResultMessage(GenericJobMessage jobMessage, GenericResultMessage resultMessage);

	public File getWorkDir();

	public boolean shouldSweepWorkDir();

	public void removeRunningJob(CompJob job);

	public LegacyRestFileBrokerClient getFileBrokerClient() throws Exception;

	public ToolboxClientComp getToolboxClient();
}