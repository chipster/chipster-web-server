import { Tags, TypeTags } from "./type-tags.js";
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

describe("Test tagging for all test files", () => {
  it("return tags", () => {
    fs.readdirSync("./test-files").forEach((filename) => {
      const tags = LocalFileTypeService.getTypeTags("./test-files/" + filename);
      console.log("\t", filename, keys(tags).join(" "), "");
      assert.notEqual(keys(tags).length, 0);
    });
  });
});
