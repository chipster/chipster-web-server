import { TypeTags } from "./type-tags.js";
import fs from "fs";

export class LocalFileTypeService {
  static getTypeTags(path: string) {
    const fastTags = TypeTags.getFastTypeTags(path);
    const data = fs.readFileSync(path, "utf8");
    const slowTags = TypeTags.getSlowTypeTags(TypeTags.parseTsv(data));
    return Object.assign({}, fastTags, slowTags);
  }
}
