package fi.csc.chipster.comp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.util.StringUtils;

import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.MetadataEntry;
import fi.csc.microarray.messaging.message.GenericResultMessage;
import fi.csc.microarray.util.ToolUtils;

public class RestPhenodataUtils {

	public static final String FILE_PHENODATA2 = "phenodata2.tsv";
	public static final String FILE_PHENODATA = "phenodata.tsv";
	
	public static final String HEADER_SAMPLE = "sample";
	public static final String HEADER_COLUMN = "column";
	public static final String HEADER_DATASET = "dataset";
	
	public static final String PREFIX_CHIP = "chip.";

	public static void writePhenodata(File jobWorkDir, HashMap<String, List<MetadataEntry>> metadata, boolean columnPhenodata, boolean fullPhenodata) throws IOException {
		
		
		// safety checks
		for (String inputId : metadata.keySet()) {
			List<MetadataEntry> datasetMetadatas = metadata.get(inputId);
			for (MetadataEntry entry : datasetMetadatas) {
				
				if (!entry.getColumn().matches(ToolUtils.NAME_PATTERN)) {
					throw new IllegalArgumentException("Dataset column " + entry.getColumn() + " contains illegal characters.");
				}
				
				if (!entry.getKey().matches(ToolUtils.NAME_PATTERN)) {
					throw new IllegalArgumentException("Phenodata column " + entry.getKey() + " contains illegal characters.");
				}
				
				if (!entry.getValue().matches(ToolUtils.NAME_PATTERN)) {
					throw new IllegalArgumentException("Phenodata value " + entry.getValue() + " contains illegal characters.");
				}
			}
		}
		
		ArrayList<String> headers = getHeaders(metadata);
		ArrayList<PhenodataRow> array = getArray(metadata, headers);	
		
		HashSet<String> uniqueInputs = new HashSet<>();
		for (PhenodataRow row : array) {
			uniqueInputs.add(row.getInputId());
		}
		
		// write column phenodata (microarray)
		if (columnPhenodata) {
			// the legacy format doesn't support multiple datasets
			if (uniqueInputs.size() > 1) {
				throw new IllegalArgumentException(
						"The tool requires phenodata in the old format, which doesn't support "
								+ "multipele datasets, but more than one input dataset contains phenodata. "
								+ "Please remove the phenodata form other input datasets to indicate which "
								+ "one to use.");
			}
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(jobWorkDir, FILE_PHENODATA)))) {

				writer.write(StringUtils.arrayToDelimitedString(headers.toArray(), "\t"));
				writer.write("\n");

				for (PhenodataRow row : array) {
					writer.write(StringUtils.arrayToDelimitedString(row.getRow().toArray(), "\t"));
					writer.write("\n");
				}
			}
		}
		
		// full phenodata supporting multiple datasets
		if (fullPhenodata) {
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(jobWorkDir, FILE_PHENODATA2)))) {

				writer.write(
						HEADER_DATASET + "\t" + 
								HEADER_COLUMN + "\t" + 
								StringUtils.arrayToDelimitedString(headers.toArray(), "\t"));

				writer.write("\n");

				for (PhenodataRow row : array) { 
					writer.write(
							row.getInputId() + "\t" + 
									row.getColumn() + "\t" + 
									StringUtils.arrayToDelimitedString(row.getRow().toArray(), "\t"));

					writer.write("\n");
				}
			}
		}
	}

	private static ArrayList<String> getHeaders(HashMap<String, List<MetadataEntry>> metadata) {
		LinkedHashSet<String> headers = new LinkedHashSet<>();
		for (List<MetadataEntry> entries : metadata.values()) {
			for (MetadataEntry entry : entries) {
				headers.add(entry.getKey());
			}
		}
		return new ArrayList<String>(headers);
	}

	public static class PhenodataRow {
		private ArrayList<String> row;
		private String inputId;
		private String column;

		public PhenodataRow(String inputId, String column, int length) {
			this.inputId = inputId;
			this.column = column;
			this.row = new ArrayList<>(Arrays.asList(new String[length]));
		}

		public ArrayList<String> getRow() {
			return row;
		}

		public void setRow(ArrayList<String> row) {
			this.row = row;
		}

		public String getInputId() {
			return inputId;
		}

		public void setInputId(String inputId) {
			this.inputId = inputId;
		}

		public String getColumn() {
			return column;
		}

		public void setColumn(String column) {
			this.column = column;
		}
	}

	private static ArrayList<PhenodataRow> getArray(HashMap<String, List<MetadataEntry>> metadata,
			ArrayList<String> headers) {
		ArrayList<PhenodataRow> phenodata = new ArrayList<>();
		for (String inputId : metadata.keySet()) {
			for (MetadataEntry entry : metadata.get(inputId)) {
				PhenodataRow row = getRow(inputId, entry.getColumn(), phenodata, headers);
				row.getRow().set(headers.indexOf(entry.getKey()), entry.getValue());
			}
		}
		return phenodata;
	}

	private static PhenodataRow getRow(String inputId, String column, ArrayList<PhenodataRow> phendoata,
			ArrayList<String> headers) {
		for (PhenodataRow row : phendoata) {
			boolean columnEquals = false;
			if (column != null) {
				columnEquals = column.equals(row.getColumn());
			} else {
				columnEquals = (row.getColumn() == null);
			}

			if (inputId.equals(row.getInputId()) && columnEquals) {
				return row;
			}
		}

		PhenodataRow row = new PhenodataRow(inputId, column, headers.size());
		phendoata.add(row);
		return row;
	}

	public static List<MetadataEntry> parseMetadata(File workinDir, String inputId) throws FileNotFoundException, IOException {
		
		File phenodata1 = new File(workinDir, FILE_PHENODATA);
		File phenodata2 = new File(workinDir, FILE_PHENODATA2);
		File phenodata = null;
		boolean isPhenodata2 = false;
		
		// old or new format?
		// we should really check if the tool description has these outputs
		if (phenodata1.exists()) {
			phenodata = phenodata1;
		}
		
		if (phenodata2.exists()) {
			phenodata = phenodata2;
			isPhenodata2 = true;
		}
		
		if (phenodata != null && phenodata.exists()) {
			try (InputStream is = new FileInputStream(phenodata)) {
				return parseMetadata(is, isPhenodata2, inputId);
			}
		}
		return new ArrayList<MetadataEntry>();
	}

	public static ArrayList<MetadataEntry> parseMetadata(InputStream is, boolean isPhenodata2, String inputId) throws IOException {
	
		ArrayList<MetadataEntry> metadata = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
			String line;
			List<String> headers = null;
			while ((line = reader.readLine()) != null) {
				String[] splitted = line.split("\t");
				if (headers == null) {
					headers = Arrays.asList(splitted);
					continue;
				}

				String rowInputId = null;
				String column = null;
				if (isPhenodata2) {
					rowInputId = splitted[headers.indexOf(HEADER_DATASET)];
					column = splitted[headers.indexOf(HEADER_COLUMN)];
				} else {
					rowInputId = inputId;
					column = PREFIX_CHIP + splitted[headers.indexOf(HEADER_SAMPLE)];
				}
				
				if (!inputId.equals(rowInputId)) {
					continue;
				}

				for (String header : headers) {
					if (HEADER_DATASET.equals(header) || HEADER_COLUMN.equals(header)) {
						continue;
					}
					
					MetadataEntry entry = new MetadataEntry();
					entry.setColumn(column);
					entry.setKey(header);
					entry.setValue(splitted[headers.indexOf(header)]);
					
					metadata.add(entry);
				}
			}
		}
		return metadata;
	}

	public static void derivePhenodata(UUID sessionId, List<Input> inputs, GenericResultMessage result, SessionDbClient sessionDbClient) throws RestException {
		
		ArrayList<MetadataEntry> derivedMetadata = new ArrayList<>();
		
		// combine metadata of all inputs
		for (Input input : inputs) {
			Dataset dataset = sessionDbClient.getDataset(sessionId, UUID.fromString(input.getDatasetId()));
			if (dataset.getMetadata() == null ) {
				continue;
			}
			for (MetadataEntry entry : dataset.getMetadata()) {
				// if there are conflicts, the first one wins
				if (!contains(derivedMetadata, entry)) {
					derivedMetadata.add(entry);
				}
			}
		}
		
		// use this phenodata for all outputs
		for (String outputName : result.getOutputNames()) {
			String datasetId = result.getDatasetId(outputName);			
			Dataset dataset = sessionDbClient.getDataset(sessionId, UUID.fromString(datasetId));
			dataset.setMetadata(derivedMetadata);
			sessionDbClient.updateDataset(sessionId, dataset);
		}
	}

	private static boolean contains(ArrayList<MetadataEntry> metadata, MetadataEntry search) {
		for (MetadataEntry entry : metadata) {
			if (search.getKey().equals(entry.getKey()) && search.getColumn().equals(entry.getColumn())) {
				return true;
			}
		}
		return false;
	}
}
