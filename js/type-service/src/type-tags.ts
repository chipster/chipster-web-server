import { Logger } from "chipster-nodejs-core/lib/logger.js";
import { fileURLToPath } from "url";

const logger = Logger.getLogger(fileURLToPath(import.meta.url));

export class Tag {
  constructor(public id: string, public extensions: string[]) {}
}

// tags in an object for code completion
export const Tags = {
  // simple types are recognized with the file extension
  // list for other text types below
  // also any other file that can be shown as ascii text
  TEXT: new Tag("TEXT", [".txt"]),
  // only files with .txt file extension
  TEXT_STRICT: new Tag("TEXT_STRICT", [".txt"]),
  TAR: new Tag("TAR", [".tar"]),
  DAT: new Tag("DAT", [".dat"]),
  WEE: new Tag("WEE", [".wee"]),
  SEQ: new Tag("SEQ", [".seq"]),
  LOG: new Tag("LOG", [".log"]),
  TSV: new Tag("TSV", [".tsv"]),
  CSV: new Tag("CSV", [".csv"]),
  PNG: new Tag("PNG", [".png"]),
  GIF: new Tag("GIF", [".gif"]),
  JPEG: new Tag("JPEG", [".jpg", ".jpeg"]),
  PDF: new Tag("PDF", [".pdf"]),
  HTML: new Tag("HTML", [".html", ".htm"]),
  TRE: new Tag("TRE", [".tre"]),
  AFFY: new Tag("AFFY", [".cel"]),
  BED: new Tag("BED", [".bed"]),
  GTF: new Tag("GTF", [".gtf", ".gff", ".gff2", ".gff3"]),
  FASTA: new Tag("FASTA", [".fasta", ".fa", ".fna", ".fsa", ".mpfa"]),
  FAI: new Tag("FAI", [".fai"]),
  FASTQ: new Tag("FASTQ", [".fastq", ".fq"]),
  GZIP: new Tag("GZIP", [".gz", ".gzip"]),
  VCF: new Tag("VCF", [".vcf"]),
  BAM: new Tag("BAM", [".bam"]),
  SAM: new Tag("SAM", [".sam"]),
  QUAL: new Tag("QUAL", [".qual"]),
  MOTHUR_OLIGOS: new Tag("MOTHUR_OLIGOS", [".oligos"]),
  MOTHUR_NAMES: new Tag("MOTHUR_NAMES", [".names"]),
  MOTHUR_GROUPS: new Tag("MOTHUR_GROUPS", [".groups"]),
  MOTHUR_STABILITY: new Tag("MOTHUR_STABILITY", [".files"]),
  MOTHUR_COUNT: new Tag("MOTHUR_COUNT", [".count_table"]),
  MOTHUR_SHARED: new Tag("MOTHUR_SHARED", [".shared"]),
  MOTHUR_TAXONOMY: new Tag("MOTHUR_TAXONOMY", [".taxonomy"]),
  SFF: new Tag("SFF", [".sff"]),
  // RData format used in phyloseq tools
  R_RDA: new Tag("R_RDA", [".Rda"]),
  MD5: new Tag("MD5", [".md5"]),

  // complex types are defined here for autocompletion, but have to be checked separately
  GENELIST: new Tag("GENELIST", []),
  GENE_EXPRS: new Tag("GENE_EXPRS", []),
  CDNA: new Tag("CDNA", []),
  PHENODATA: new Tag("PHENODATA", []),
  GENERIC: new Tag("GENERIC", []),
  PVALUE_AND_FOLD_CHANGE: new Tag("PVALUE_AND_FOLD_CHANGE", []),
  COLUMN_TITLES: new Tag("COLUMN_TITLES", []),
  SKIP_LINES: new Tag("SKIP_LINES", []),
  NO_TITLE_ROW: new Tag("NO_TITLE_ROW", []),
};

// types that are tagged even if they are gzipped, for example .fasta.gz -> Tags.FASTA
const GZIP_SUPPORTED_TYPES = new Set([
  Tags.FASTA,
  Tags.FASTQ,
  Tags.MOTHUR_COUNT,
  Tags.MOTHUR_GROUPS,
  Tags.GTF,
]);

const TEXT_TYPES = new Set([
  Tags.TSV,
  Tags.CSV,
  Tags.BED,
  Tags.GTF,
  Tags.FASTA,
  Tags.FAI,
  Tags.FASTQ,
  Tags.SAM,
  Tags.QUAL,
  Tags.TRE,
  Tags.VCF,
  Tags.MOTHUR_COUNT,
  Tags.MOTHUR_GROUPS,
  Tags.MOTHUR_NAMES,
  Tags.MOTHUR_OLIGOS,
  Tags.MOTHUR_STABILITY,
  Tags.MOTHUR_SHARED,
  Tags.MOTHUR_TAXONOMY,
  Tags.LOG,
  Tags.SEQ,
  Tags.WEE,
  Tags.DAT,
  Tags.MD5,
]);

const PVALUE_HEADERS = ["p.", "pvalue", "padj", "PValue", "FDR"];
const FOLD_CHANGE_HEADERS = ["FC", "log2FoldChange", "logFC"];

export class TypeTags {
  static getFastTypeTags(name: string): Object {
    let typeTags = {}; // TODO refactor to Map

    // add simple type tags based on file extensions
    // TODO refactor
    for (let tagKey in Tags) {
      // for-in to iterate object keys
      for (let extension of Tags[tagKey].extensions) {
        // for-of to iterate array items
        if (name) {
          // check extension, also possibly add TEXT tag
          if (name.toLowerCase().endsWith(extension.toLowerCase())) {
            typeTags[tagKey] = null;
            if (TEXT_TYPES.has(Tags[tagKey])) {
              typeTags[Tags.TEXT.id] = null;
              break;
            }
          }
          // check extension with gz, never add TEXT tag
          else if (
            TypeTags.endsWithExtensionAndGzip(name, extension, Tags[tagKey])
          ) {
            typeTags[tagKey] = null;
          }
        }
      }
    }

    if (Tags.VCF.id in typeTags) {
      typeTags[Tags.SKIP_LINES.id] = "##";
    }

    if (Tags.GTF.id in typeTags) {
      typeTags[Tags.COLUMN_TITLES.id] =
        "seqname\tsource\tfeature\tstart\tend\tscore\tstrand\tframe\tattribute";
      // this is now set also for .gtf.gz, whether that's good or bad
      // our Ensembl GTF uses '#!', GFF3 uses '##'
      typeTags[Tags.SKIP_LINES.id] = "#";
    }

    if (Tags.BED.id in typeTags) {
      typeTags[Tags.COLUMN_TITLES.id] =
        "chrom\tchromStart\tchromEnd\tname\tscore\tstrand\tthickStart\tthickEnd\titemRgb\tblockCount\tblockSizes\tblockStarts";
    }

    if (Tags.FAI.id in typeTags) {
      typeTags[Tags.COLUMN_TITLES.id] =
        "NAME\tLENGTH\tOFFSET\tLINEBASES\tLINEWIDTH";
    }

    if (Tags.SAM.id in typeTags) {
      // simply add enough optional TAG columns, because spreadsheet doesn't seem to care about extra headers
      typeTags[Tags.COLUMN_TITLES.id] =
        "QNAME\tFLAG\tRNAME\tPOS\tMAPQ\tCIGAR\tRNEXT\tPNEXT\tTLEN\tSEQ\tQUAL\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG\tTAG";
      typeTags[Tags.SKIP_LINES.id] = "@";
    }

    if (Tags.MOTHUR_GROUPS.id in typeTags) {
      typeTags[Tags.NO_TITLE_ROW.id] = "";
    }

    if (Tags.MOTHUR_NAMES.id in typeTags) {
      typeTags[Tags.NO_TITLE_ROW.id] = "";
    }

    if (Tags.MOTHUR_OLIGOS.id in typeTags) {
      typeTags[Tags.NO_TITLE_ROW.id] = "";
    }

    if (Tags.MOTHUR_STABILITY.id in typeTags) {
      typeTags[Tags.NO_TITLE_ROW.id] = "";
      typeTags[Tags.SKIP_LINES.id] = "#";
    }

    return typeTags;
  }

  static getSlowTypeTags(table: string[][]) {
    let slowTags = {};

    let headers = table[0];
    let firstRow = table[1];

    if (
      headers.indexOf("identifier") !== -1 ||
      headers.indexOf(" ") !== -1 ||
      (firstRow && headers.length === firstRow.length - 1)
    ) {
      slowTags[Tags.GENELIST.id] = null;
    }

    if (headers.filter((header) => header.startsWith("chip.")).length > 0) {
      slowTags[Tags.GENE_EXPRS.id] = null;
    }

    if (headers.indexOf("sample") !== -1) {
      slowTags[Tags.CDNA.id] = null;
    }

    if (TypeTags.pValueAndFoldChangeCompatible(headers)) {
      slowTags[Tags.PVALUE_AND_FOLD_CHANGE.id] = null;
    }

    return slowTags;
  }

  static parseTsv(data: string): string[][] {
    let rows = data.split("\n", 2);
    return rows.map((row) => row.split("\t"));
  }

  static pValueAndFoldChangeCompatible(headers: string[]) {
    return (
      PVALUE_HEADERS.some((pValueHeader) =>
        headers.some((header) => header.startsWith(pValueHeader))
      ) &&
      FOLD_CHANGE_HEADERS.some((foldChangeHeader) =>
        headers.some((header) => header.startsWith(foldChangeHeader))
      )
    );
  }

  static endsWithExtensionAndGzip(
    filename: string,
    extension: string,
    tag: Tag
  ): boolean {
    return (
      GZIP_SUPPORTED_TYPES.has(tag) &&
      Tags.GZIP.extensions.some((gzipExtension) =>
        filename
          .toLowerCase()
          .endsWith(extension.toLowerCase() + gzipExtension.toLowerCase())
      )
    );
  }
}
