package fi.csc.chipster.comp;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.validation.ValidationException;

import org.junit.Assert;
import org.junit.Test;

public class PhenodataUtilsTest {

	private static final String PHENODATA_FILE = "phenodata-without-original-names.tsv";
	private static final String PHENODATA_FILE_INVALID = "phenodata-without-original-names-invalid.tsv";
	private static final String INPUT_NAMES_FILE = "chipster-inputs.tsv";

	@Test
	public void testProcessPhenodata() throws IOException, URISyntaxException {
		PhenodataUtils.processPhenodata(getPath(PHENODATA_FILE));
	}

	@Test
	public void testProcessInvalid() throws IOException {

		Assert.assertThrows(ValidationException.class,
				() -> PhenodataUtils.processPhenodata(getPath(PHENODATA_FILE_INVALID)));
	}

	@Test
	public void testParseInputNames() throws IOException, URISyntaxException {
		PhenodataUtils.parseInputNames(getPath(INPUT_NAMES_FILE));
	}

	private Path getPath(String fileName) throws URISyntaxException {
		return Paths.get(getClass().getClassLoader().getResource(fileName).toURI());

	}
}
