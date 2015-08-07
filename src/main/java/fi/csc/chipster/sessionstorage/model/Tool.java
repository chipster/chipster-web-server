package fi.csc.chipster.sessionstorage.model;

import java.util.List;

public class Tool {

	public String id;
	public String name;
	public String category;
	public String module;
	public String description;
	public String helpURL;
	
	public List<Parameter> parameters;
	public List<ToolInput> inputs;
	
}
