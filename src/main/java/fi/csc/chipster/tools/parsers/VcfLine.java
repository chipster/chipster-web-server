package fi.csc.chipster.tools.parsers;

import fi.csc.chipster.tools.model.Chromosome;

/**
 * This class represents a single line of Vcf file.
 * 
 * @author klemela
 */
public class VcfLine extends FileLine {

	private Chromosome chrom;
	private Long pos;
	private String id;
	private String ref;
	private String alt;
	private Float qual;
	private String filter;
	private String info;

	public Chromosome getChrom() {
		return chrom;
	}

	public void setChrom(Chromosome chrom) {
		this.chrom = chrom;
	}

	public Long getPos() {
		return pos;
	}

	public void setPos(Long pos) {
		this.pos = pos;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getRef() {
		return ref;
	}

	public void setRef(String ref) {
		this.ref = ref;
	}

	public String getAlt() {
		return alt;
	}

	public void setAlt(String alt) {
		this.alt = alt;
	}

	public Float getQual() {
		return qual;
	}

	public void setQual(Float qual) {
		this.qual = qual;
	}

	public String getFilter() {
		return filter;
	}

	public void setFilter(String filter) {
		this.filter = filter;
	}

	public String getInfo() {
		return info;
	}

	public void setInfo(String info) {
		this.info = info;
	}
}
