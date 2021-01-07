package fi.csc.chipster.comp;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import jakarta.validation.ValidationException;

import org.junit.Assert;
import org.junit.Test;

public class PhenodataUtilsTest {

	private static final String PHENODATA_FILE = "phenodata-without-original-names.tsv";
	private static final String PHENODATA_FILE_WITH_ORIGINAL_NAMES = "phenodata-with-original-names.tsv";
	private static final String PHENODATA_FILE_WITH_ORIGINAL_NAMES_WITH_DESCRIPTIONS = "phenodata-without-original-names-with-descriptions.tsv";
	private static final String PHENODATA_FILE_INVALID = "phenodata-without-original-names-invalid.tsv";
	private static final String INPUT_NAMES_FILE = "chipster-inputs.tsv";

	@Test
	public void testProcessPhenodata() throws IOException, URISyntaxException {
		PhenodataUtils.processPhenodata(getPath(PHENODATA_FILE));
	}

	@Test
	public void testOriginalNamesAlreadyExist() throws IOException, URISyntaxException {
		String result = PhenodataUtils.processPhenodata(getPath(PHENODATA_FILE_WITH_ORIGINAL_NAMES));
		Assert.assertEquals(PhenodataUtils.parse(result).get(1).get(1), "should remain here.tsv");
		Assert.assertEquals(PhenodataUtils.parse(result).get(1).get(4), "should remain here");
	}

	@Test
	public void testOnlyDescriptionAlreadyExist() throws IOException, URISyntaxException {
		String result = PhenodataUtils.processPhenodata(getPath(PHENODATA_FILE_WITH_ORIGINAL_NAMES_WITH_DESCRIPTIONS));
		Assert.assertEquals(PhenodataUtils.parse(result).get(1).get(1), "cancerGSM11814.cel");
		Assert.assertEquals(PhenodataUtils.parse(result).get(1).get(4), "original description");
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
