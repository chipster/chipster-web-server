import {TypeTags} from "./type-tags";
import fs = require('fs');


export class LocalFileTypeService {

  static getTypeTags(path: string) {
    let fastTags = TypeTags.getFastTypeTags(path);
    let data = fs.readFileSync(path, 'utf8');
    let slowTags = TypeTags.getSlowTypeTags(TypeTags.parseHeader(data));
    return Object.assign({}, fastTags, slowTags)
  }


}