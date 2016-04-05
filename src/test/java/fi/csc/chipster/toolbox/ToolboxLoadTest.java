package fi.csc.chipster.toolbox;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;

import org.junit.Test;

import fi.csc.microarray.config.ConfigurationLoader.IllegalConfigurationException;
import fi.csc.microarray.config.DirectoryLayout;

public class ToolboxLoadTest {

	
	@Test
	public void loadToolbox() throws IOException, IllegalConfigurationException, URISyntaxException {
		DirectoryLayout.uninitialise();
		DirectoryLayout.initialiseUnitTestLayout();			
		
		@SuppressWarnings("unused")
		Toolbox toolbox = new Toolbox(Paths.get("../chipster-tools/modules"));
	}
}
