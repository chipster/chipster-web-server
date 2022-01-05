package fi.csc.chipster.toolbox.runtime;

/**
 * 
 * Contains information about a single runtime
 * 
 * A runtime specifies an environment, in which a tool (often a script file)
 * is actually run. Examples of runtimes include different versions of R or
 * python runtimes.
 *
 */
public class Runtime {
	
	private String name;
	private String jobFactory;
	private String command;
	private String commandParameters;
	private String toolsBin;
	private String toolsBinPath;
	private String image;
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getJobFactory() {
		return jobFactory;
	}
	public void setJobFactory(String jobFactory) {
		this.jobFactory = jobFactory;
	}
	public String getCommand() {
		return command;
	}
	public void setCommand(String command) {
		this.command = command;
	}
	public String getCommandParameters() {
		return commandParameters;
	}
	public void setCommandParameters(String commandParameters) {
		this.commandParameters = commandParameters;
	}
	public String getToolsBin() {
		return toolsBin;
	}
	public void setToolsBin(String toolsBin) {
		this.toolsBin = toolsBin;
	}
	public String getToolsBinPath() {
		return toolsBinPath;
	}
	public void setToolsBinPath(String toolsBinPath) {
		this.toolsBinPath = toolsBinPath;
	}
	public String getImage() {
		return image;
	}
	public void setImage(String image) {
		this.image = image;
	}
}
