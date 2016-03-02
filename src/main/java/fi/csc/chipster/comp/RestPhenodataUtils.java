package fi.csc.chipster.comp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.util.StringUtils;

import fi.csc.chipster.sessiondb.model.MetadataEntry;
import fi.csc.microarray.util.ToolUtils;

public class RestPhenodataUtils {

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
			if (uniqueInputs.size() != 1) {
				throw new IllegalArgumentException(
						"The tool requires phenodata in the old format, which doesn't support "
								+ "multipele datasets, but more than one input dataset contains phenodata. "
								+ "Please remove the phenodata form other input datasets to indicate which "
								+ "one to use.");
			}
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(jobWorkDir, "phenodata.tsv")))) {

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
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(jobWorkDir, "phenodata2.tsv")))) {

				writer.write(
						"input\t" + 
								"column\t" + 
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
}
