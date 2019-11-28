package fi.csc.chipster.tools.parsers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.csc.chipster.tools.model.Chromosome;
import fi.csc.chipster.tools.model.Region;
import fi.csc.chipster.tools.model.Strand;

public class GtfLineParser extends AbstractTsvLineParser {
	
	public enum Column {
			
		SEQNAME ("seqname"), 		
		SOURCE ("source"), 
		FEATURE ("feature"),
		START("start"), 
		END ("end"), 
		SCORE ("score"), 
		STRAND ("strand"), 
		FRAME ("frame"), 
		ATTRIBUTES ("attributes");
		
		private final String name;
		
		Column(String name) {
			this.name = name;
		}
		
		String getName() {
			return name;
		}
	}

	private Map<String, String> attributes;

	@SuppressWarnings("deprecation")
	@Override
	public Region getRegion() {
		
		if (values == null ){
			return null;
			
		} else {

			long start = getLong(Column.START.ordinal());
			long end = getLong(Column.END.ordinal());
			Chromosome chr = new Chromosome(getString(Column.SEQNAME.ordinal()));
			
			String strandString = getString(Column.STRAND.ordinal());

			Strand strand = null;

			if ("+".equals(strandString)) {
				strand = Strand.FORWARD;
			}

			if ("-".equals(strandString)) {
				strand = Strand.REVERSE;
			}

			return new Region(start, end, chr, strand);
		}
	}

	@Override
	public boolean setLine(String line) {
		
		if (!line.startsWith(getHeaderStart())) {
			this.attributes = null;
		}
		
		return super.setLine(line);
	}

	public String getFeature() {
		return getString(Column.FEATURE.ordinal());
	}
	
	public String getGeneId() {
		return getAttribute("gene_id");
	}
	
	public String getTranscriptId() {
		return getAttribute("transcript_id");
	}
	
	public String getAttribute(String key) {
		if (this.attributes == null) {
			this.attributes = parseAttributes(getString(Column.ATTRIBUTES.ordinal()));			
		}
		return attributes.get(key);
	}

	public static Map<String, String> parseAttributes(String attributeString) {
	
		List<String> stringList = GtfLineParser.splitConsideringQuotes(attributeString, ';');
		
		Map<String, String> attributeMap = new HashMap<String, String>(); 

		String key = null;
		String value = null;
		int indexOfSpace = 0;
		
		for (String keyAndValue : stringList) {

			keyAndValue = keyAndValue.trim();
			indexOfSpace = keyAndValue.indexOf(" ");		

			key = keyAndValue.substring(0, indexOfSpace).trim();
			value = keyAndValue.substring(indexOfSpace + 1);
			
			attributeMap.put(key, value);
		}

		return attributeMap;
	}

	@Override
	public String getHeaderStart() {
		return "#";
	}

	@Override
	public FileLine getFileLine() {
		// TODO Auto-generated method stub
		return null;
	}
	
	public static List<String> splitConsideringQuotes(String input, char delimiter) {
		// based on http://stackoverflow.com/questions/1757065/splitting-a-comma-separated-string-but-ignoring-commas-in-quotes
		List<String> result = new ArrayList<String>();
		int start = 0;
		boolean inQuotes = false;
		
		for (int current = 0; current < input.length(); current++) {
			
		    if (input.charAt(current) == '\"') {
		    	inQuotes = !inQuotes; // toggle state
		    }
		    
		    boolean atLastChar = (current == input.length() - 1);
		    
		    	
		    if (input.charAt(current) == delimiter && !inQuotes) {
		        result.add(input.substring(start, current).replace("\"", ""));
		        start = current + 1;
		    } else if (atLastChar) {
		    	result.add(input.substring(start).replace("\"", ""));
		    }
		}
		return result;
	}
}
