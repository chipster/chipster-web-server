import { Tags, TypeTagMap, TypeTags } from "./type-tags.js";
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { LocalFileTypeService } from "./local-file-type-service.js";
import fs from "fs";
import { keys } from "lodash-es";

describe("Test pValue and fold change tagging", () => {
  const pvalueAndFCHeader = ["pppvalue", "FaaC", "logFC", "happy", "times", "p.test"];
  const noPvalueHeader = ["logFC", "summer"];
  it("return true for header with pValue and fold change columns", () => {
    assert.equal(TypeTags.pValueAndFoldChangeCompatible(pvalueAndFCHeader), true);
  });
  it("return false for header with no pValue column", () => {
    assert.equal(TypeTags.pValueAndFoldChangeCompatible(noPvalueHeader), false);
  });
});

describe("Test fast tags", () => {
  it("return PNG for .png files", () => {
    assert.deepEqual(keys(TypeTags.getFastTypeTags("image.png")), [Tags.PNG.id]);
  });
});

/*
The tags of every file in test-files

Tagging is easy to change by accident, for example by giving an extension to
another tag, so the test names the tags of each file. Checking only that there
are some would pass even if every file got the wrong ones.
*/
const EXPECTED_TAGS: { [filename: string]: TypeTagMap } = {
  "extract.tsv": {
    [Tags.TSV.id]: null,
    [Tags.TEXT.id]: null,
    [Tags.GENELIST.id]: null,
    [Tags.GENE_EXPRS.id]: null,
  },
  "image.png": { [Tags.PNG.id]: null },
  "two-sample.tsv": {
    [Tags.TSV.id]: null,
    [Tags.TEXT.id]: null,
    [Tags.GENELIST.id]: null,
    [Tags.GENE_EXPRS.id]: null,
    [Tags.PVALUE_AND_FOLD_CHANGE.id]: null,
  },
  "unique-genes.tsv": {
    [Tags.TSV.id]: null,
    [Tags.TEXT.id]: null,
    [Tags.GENELIST.id]: null,
  },
};

describe("Test tagging for all test files", () => {
  it("return the tags of every test file", () => {
    const filenames = fs.readdirSync("./test-files").sort();

    // a new test file has to get an expectation of its own, instead of
    // being tagged without anything checking the result
    assert.deepEqual(filenames, Object.keys(EXPECTED_TAGS).sort());

    for (const filename of filenames) {
      const tags = LocalFileTypeService.getTypeTags("./test-files/" + filename);
      assert.deepEqual(tags, EXPECTED_TAGS[filename], filename);
    }
  });
});
