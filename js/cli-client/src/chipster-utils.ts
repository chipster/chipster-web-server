import { Subject } from "rxjs";
const read = require('read');

export default class ChipsterUtils {

    static printStatus(args, status, value = null) {
        if (!args.quiet) {
          if (value != null) {
            console.log(status + ': \t' + value);
          } else {
            console.log(status);
          }
        } else if (value != null) {
          console.log(value);
        }
    }
    
    static printTable(obj, keys, widths) {
        let row = '';
        for (let i = 0; i < keys.length; i++) {
          const value = obj[keys[i]];
          if (widths.length > i) {
            const nonNullValue: any = '' + value;
            row += nonNullValue.padEnd(widths[i]);
          } else {
            row += value;
          }
        }
        console.log(row);
      }
    
      static toHumanReadable(value) {
        if (Number.isInteger(value)) {
          if (value > Math.pow(1024, 3)) {
            value = Math.round(value / Math.pow(1024, 3) * 10) / 10 + ' G';
          } else if (value > Math.pow(1024, 2)) {
            value = Math.round(value / Math.pow(1024, 2) * 10) / 10 + ' M';
          } else if (value > 1024) {
            value = Math.round(value / 1024 * 10) / 10 + ' k'
          }
        }
        return value;
      }
    
      static getPrompt(prompt, defaultValue = '', silent = false) {
        let subject = new Subject();
    
        read({ prompt: prompt, silent: silent, default: defaultValue}, function(err, line) {
          if (err) {
            subject.error(err);
          }
          subject.next(line);
          subject.complete();
        });
    
        return subject;
      }
    
      static parseArgArray(array) {
        const map = new Map();
        if (array) {
          array.forEach(inputArg => {
            const key = inputArg.slice(0, inputArg.indexOf('='));
            const value = inputArg.slice(inputArg.indexOf('=') + 1);
            map.set(key, value);
          });
        }
        return map;
      }
    
      static fixUri(uri) {
        if (!uri.startsWith('http')) {
          // add protocol
          uri = 'https://' + uri;
        }
        if (uri.endsWith('/')) {
          // remove trailing slash
          uri = uri.slice(0, -1);
        }
        return uri;
      }
}