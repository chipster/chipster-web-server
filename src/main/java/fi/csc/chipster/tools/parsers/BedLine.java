package fi.csc.chipster.tools.parsers;

import java.awt.Color;
import java.util.List;

import fi.csc.chipster.tools.model.Chromosome;
import fi.csc.chipster.tools.model.Strand;

/**
 * A class which represents single line of bed file.
 * 
 * @author klemela
 */
public class BedLine extends FileLine {

	private Chromosome chrom;
	private Long chromStart;
	private Long chromEnd;
	private String name;
	private Float score;
	private Strand strand;
	private Long thickStart;
	private Long thickEnd;
	private Color itemRgb;
	private Integer blockCount;
	private List<Long> blockSizes;
	private List<Long> blockStarts;

	public Chromosome getChrom() {
		return chrom;
	}

	public void setChrom(Chromosome chromosome) {
		this.chrom = chromosome;
	}

	public Long getChromStart() {
		return chromStart;
	}

	public void setChromStart(Long chromStart) {
		this.chromStart = chromStart;
	}

	public Long getChromEnd() {
		return chromEnd;
	}

	public void setChromEnd(Long chromEnd) {
		this.chromEnd = chromEnd;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Float getScore() {
		return score;
	}

	public void setScore(Float score) {
		this.score = score;
	}

	public Strand getStrand() {
		return strand;
	}

	public void setStrand(Strand strand) {
		this.strand = strand;
	}

	public Long getThickStart() {
		return thickStart;
	}

	public void setThickStart(Long thickStart) {
		this.thickStart = thickStart;
	}

	public Long getThickEnd() {
		return thickEnd;
	}

	public void setThickEnd(Long thickEnd) {
		this.thickEnd = thickEnd;
	}

	public Color getItemRgb() {
		return itemRgb;
	}

	public void setItemRgb(Color itemRgb) {
		this.itemRgb = itemRgb;
	}

	public Integer getBlockCount() {
		return blockCount;
	}

	public void setBlockCount(Integer blockCount) {
		this.blockCount = blockCount;
	}

	public List<Long> getBlockSizes() {
		return blockSizes;
	}

	public void setBlockSizes(List<Long> blockSizes) {
		this.blockSizes = blockSizes;
	}

	public List<Long> getBlockStarts() {
		return blockStarts;
	}

	public void setBlockStarts(List<Long> blockStarts) {
		this.blockStarts = blockStarts;
	}
}
