package fi.csc.chipster.comp;

import java.io.IOException;
import java.nio.file.Paths;

import javax.validation.ValidationException;

import org.junit.Assert;
import org.junit.Test;

public class PhenodataUtilsTest {

	//
	private static final String PHENODATA_FILE = "phenodata.tsv";
	private static final String PHENODATA_FILE_INVALID = "phenodata-invalid.tsv";
	private static final String INPUT_NAMES_FILE = "chipster-inputs.tsv";

	@Test
	public void testParse() throws IOException {
		PhenodataUtils.processPhenodata(Paths.get(PHENODATA_FILE));
	}

	@Test
	public void testParseFailing() throws IOException {
		Assert.assertThrows(ValidationException.class,
				() -> PhenodataUtils.processPhenodata(Paths.get(PHENODATA_FILE_INVALID)));
	}

	@Test
	public void testParseInputNames() throws IOException {
		PhenodataUtils.parseInputNames(Paths.get(INPUT_NAMES_FILE));
	}

}
