package fi.csc.chipster.toolbox;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.toolbox.runtime.RuntimeRepository;

public class ToolboxLoadTest {

	
	@Test
	public void loadToolbox() throws IOException, URISyntaxException {
		Toolbox.loadModuleDescriptions(Paths.get("../chipster-tools/tools"), new File("."), new RuntimeRepository(new Config()));	
	}

	public static void main(String[] args) throws IOException {
		Toolbox.loadModuleDescriptions(Paths.get("../chipster-tools/tools"), new File("."), new RuntimeRepository(new Config()));
	}

}



