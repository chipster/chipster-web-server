import { Subject } from "rxjs";
import { map, mergeMap } from "rxjs/operators";
import fs from "fs";
import os from "os";
import path from "path";
const ENV_FILE = "chipster-cli-env.json";

export default class CliEnvironment {
  get(key: string) {
    return this.read().pipe(map((env) => env[key]));
  }

  getEnvFile() {
    const homedir = os.homedir();
    return path.join(homedir, ".chipster", "cli", ENV_FILE);
  }

  read() {
    let subject = new Subject();
    fs.readFile(this.getEnvFile(), "utf8", (err, data) => {
      if (err) {
        if (err.code === "ENOENT") {
          subject.next({});
          subject.complete();
        } else {
          throw new Error(
            "failed to read the cli environment file" + ENV_FILE + " " + err,
          );
        }
      } else {
        subject.next(JSON.parse(data));
        subject.complete();
      }
    });
    return subject;
  }

  set(key: string, value: string) {
    return this.read().pipe(
      mergeMap((env) => {
        env[key] = value;
        return this.write(env);
      }),
    );
  }

  private write(object) {
    let subject = new Subject();
    let json = JSON.stringify(object);

    if (!fs.existsSync(this.getEnvFile())) {
      console.log("creating cli environement file " + this.getEnvFile());
      fs.mkdirSync(path.dirname(this.getEnvFile()), { recursive: true });
    }

    fs.writeFile(this.getEnvFile(), json, "utf8", (err) => {
      if (err) {
        throw new Error("writing env file failed" + err);
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
