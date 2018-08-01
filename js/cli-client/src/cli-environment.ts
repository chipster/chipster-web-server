
import {mergeMap, map} from 'rxjs/operators';
import {Subject} from "rxjs";
var fs = require('fs');

const ENV_FILE = 'chipster-cli-env.json';

export default class CliEnvironment {

  get(key: string) {
    return this.read().pipe(
      map(env => env[key]));
  }

  read() {
    let subject = new Subject();
    fs.readFile(ENV_FILE, 'utf8', (err, data) => {
      if (err) {
        if (err.code === 'ENOENT') {
          console.log('creating cli environement file ' + ENV_FILE);
          subject.next({});
          subject.complete();
        } else {
          throw new Error('failed to read the cli environment file' + ENV_FILE + ' ' + err);
        }
      } else {
        subject.next(JSON.parse(data));
        subject.complete();
      }
    });
    return subject;
  }

  set(key: string, value: string) {
    return this.read().pipe(mergeMap(env => {
      env[key] = value;
      return this.write(env);
    }));
  }

  private write(object) {
    let subject = new Subject();
    let json = JSON.stringify(object);

    fs.writeFile(ENV_FILE, json, 'utf8', (err) => {
      if (err) {
        throw new Error('writing env file failed' + err);
      }
      subject.next(object);
      subject.complete();
    });
    return subject;
  }

  clear() {
    return this.write({});
  }
}
