package fi.csc.chipster.toolbox;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;

import org.junit.Test;

import fi.csc.microarray.config.ConfigurationLoader.IllegalConfigurationException;

public class ToolboxLoadTest {

	
	@Test
	public void loadToolbox() throws IOException, IllegalConfigurationException, URISyntaxException {
		Toolbox.loadModuleDescriptions(Paths.get("../chipster-tools/tools"));	
	}

	public static void main(String[] args) throws IOException {
		Toolbox.loadModuleDescriptions(Paths.get("../chipster-tools/tools"));
	}

}



