package fi.csc.chipster.toolbox;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;

import org.junit.Test;

public class ToolboxLoadTest {

	
	@Test
	public void loadToolbox() throws IOException, URISyntaxException {
		Toolbox.loadModuleDescriptions(Paths.get("../chipster-tools/tools"), new File("."));	
	}

	public static void main(String[] args) throws IOException {
		Toolbox.loadModuleDescriptions(Paths.get("../chipster-tools/tools"), new File("."));
	}

}



