
import {Tags, TypeTags} from './type-tags';
import { expect } from 'chai';
import {LocalFileTypeService} from "./local-file-type-service";
import fs = require('fs');
import * as _ from "lodash";

describe('Test pValue and fold change tagging', () => {
  let pvalueAndFCHeader = ["pppvalue", "FaaC", "logFC", "happy", "times", "p.test"];
  let noPvalueHeader = ["logFC", "summer"];
  it('return true for header with pValue and fold change columns', () => {
    expect(TypeTags.pValueAndFoldChangeCompatible(pvalueAndFCHeader)).to.equal(true);
  });
  it('return false for header with no pValue column', () => {
    expect(TypeTags.pValueAndFoldChangeCompatible(noPvalueHeader)).to.equal(false);
  });
});

describe('Test fast tags', () => {
  it('return PNG for .png files', () => {
    expect(TypeTags.getFastTypeTags('image.png')).to.have.keys(Tags.PNG.id);
  })
});

describe('Test tagging for all test files', () => {
  it('return tags', () => {
    fs.readdirSync('./test-files').forEach(filename => {
      let tags =  LocalFileTypeService.getTypeTags('./test-files/' + filename);
      console.log("\t", filename, _.keys(tags).reduce((all, current) => (all += " " + current)), "");
      expect(tags).not.to.be.empty;
      });
  })
});

