package fi.csc.chipster.comp;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;

import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;
import com.univocity.parsers.tsv.TsvWriter;
import com.univocity.parsers.tsv.TsvWriterSettings;

import jakarta.validation.ValidationException;

public class PhenodataUtils {

	private static final String ORIGINAL_NAME = "original_name";
	private static final String DESCRIPTION = "description";
	private static final String SAMPLE = "sample";
	private static final String INPUT_NAMES_FILE = "chipster-inputs.tsv";

	public static String processPhenodata(Path phenodataFile) throws IOException {
		List<List<String>> rows = parse(phenodataFile);

		// throws ValidationException if fails
		validatePhenodata(rows);

		Path inputNamesFile = phenodataFile.getParent().resolve(INPUT_NAMES_FILE);
		Map<String, String> inputNames = parseInputNames(inputNamesFile);

		addOriginalNameAndDescription(rows, inputNames);

		// generate finished phenodata string
		StringWriter stringWriter = new StringWriter();
		new TsvWriter(stringWriter, new TsvWriterSettings()).writeRowsAndClose(rows);
		return stringWriter.toString();
	}

	public static List<List<String>> parse(String phenodataString) throws IOException {
		return parse(new StringReader(phenodataString));
	}

	public static List<List<String>> parse(Reader reader) throws IOException {

		// use ArrayLists to make list modifications easier
		return new TsvParser(new TsvParserSettings()).parseAll(reader).stream()
				.map(row -> new ArrayList<String>(Arrays.asList(row))).collect(Collectors.toList());
	}

	public static List<List<String>> parse(Path phenodataFile) throws IOException {
		return parse(new BufferedReader(new FileReader(phenodataFile.toFile())));
	}

	private static void validatePhenodata(List<List<String>> phenodata) {
		if (phenodata.size() <= 1) {
			return;
		}

		int columnCount = phenodata.get(0).size();
		if (!phenodata.stream().allMatch(row -> row.size() == columnCount)) {
			throw new ValidationException("Mismatching column count");
		}
	}

	private static void addOriginalNameAndDescription(List<List<String>> rows, Map<String, String> inputNames) {
		List<String> headers = rows.get(0);

		// add original_name column if it doesn't exist
		if (headers.indexOf(ORIGINAL_NAME) == -1) {
			int indexOfSample = headers.indexOf(SAMPLE);
			headers.add(indexOfSample + 1, ORIGINAL_NAME);

			rows.subList(1, rows.size()).stream().forEach(row -> {
				String originalName = inputNames.get(row.get(indexOfSample));
				row.add(indexOfSample + 1, originalName);
			});
		}

		// if no description column, add it as original name without file extension
		// original name was added above or was there already
		if (headers.indexOf(DESCRIPTION) == -1) {
			int indexOfOriginalName = headers.indexOf(ORIGINAL_NAME);
			headers.add(DESCRIPTION);

			rows.subList(1, rows.size()).stream().forEach(row -> {
				String originalName = row.get(indexOfOriginalName);
				String description = FilenameUtils.removeExtension(originalName);
				row.add(description);
			});
		}
	}

	public static Map<String, String> parseInputNames(Path path) {
		// add validation?
		List<String[]> allRows = new TsvParser(new TsvParserSettings()).parseAll(path.toFile());

		Map<String, String> inputNamesMap = new HashMap<String, String>();
		allRows.forEach(row -> {
			inputNamesMap.put(row[0], row[1]);
		});

		return inputNamesMap;
	}
}
