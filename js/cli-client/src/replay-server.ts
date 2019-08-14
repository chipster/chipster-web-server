import { Logger, RestClient } from "chipster-nodejs-core";
import { mergeMap } from "rxjs/operators";
import { VError } from "verror";
import ReplaySession from "./replay-session";

const ArgumentParser = require("argparse").ArgumentParser;
const logger = Logger.getLogger(__filename, "logs/chipster.log");
const schedule = require("node-schedule");
const serveIndex = require("serve-index");
const express = require("express");

export default class ReplayServer {
  startTime: Date;
  resultsPath: string;
  influxdbUrl: string;

  constructor() {
    Logger.addLogFile();

    this.parseCommand();
  }

  parseCommand() {
    let parser = new ArgumentParser({
      version: "0.0.1",
      addHelp: true,
      description: "Chipster session replay server"
    });

    parser.addArgument(["URL"], { help: "url of the app server" });
    parser.addArgument(["--username", "-u"], {
      help: "username for the server"
    });
    parser.addArgument(["--password", "-p"], {
      help: "password for the server"
    });
    parser.addArgument(["--results", "-r"], {
      help:
        "replay session prefix and test result directory (both cleared automatically)",
      defaultValue: "results/default-test-set"
    });
    parser.addArgument(["--filter", "-F"], {
      help:
        "replay all sessions stored on the server starting with this string",
      action: "append"
    });
    parser.addArgument(["--schedule", "-s"], {
      help: "how often to run the tests, in cron format",
      defaultValue: "* * * * *"
    });
    parser.addArgument(["--influxdb", "-i"], {
      help: "influxdb url for statistics",
      defaultValue: "http://influxdb:8086/write?db=db"
    });
    parser.addArgument(["--port", "-P"], {
      help: "HTTP port for serving the result files",
      defaultValue: "9000"
    });

    let args = parser.parseArgs();

    this.startTime = new Date();
    this.influxdbUrl = args.influxdb;

    logger.info(
      "start server for sharing the result files, in port",
      args.port
    );

    var app = express();
    app.use(
      "/",
      express.static("results"),
      serveIndex("results", { icons: true, view: "details" })
    );
    app.listen(parseInt(args.port));

    logger.info("schedule session replay test", args.schedule);
    const replaySessionJob = schedule.scheduleJob(args.schedule, () => {
      const replaySession = new ReplaySession();

      logger.info("ReplayServer.parseCommand()");
      replaySession
        .replay(
          args.URL,
          args.username,
          args.password,
          false,
          1,
          false,
          args.results,
          null,
          args.filter,
          null
        )
        .pipe(
          mergeMap((stats: Map<string, number>) => {
            return this.postToInflux(stats, args.filter);
          })
        )
        .subscribe(
          () => logger.info("session replay done"),
          err => logger.error(new VError(err, "session replay error")),
          () => logger.info("session replay completed")
        );
    });
    logger.info("next invocation", replaySessionJob.nextInvocation());

    // these shouldn't happen if RestClient handled errrors correctly
    // apparently it doesn't, so let's catch them to prevent crashing this server after each tool failure
    process.on("uncaughtException", err => {
      logger.error(new VError(err, "uncaught exception"));
    });
  }

  postToInflux(stats: Map<string, number>, testSet: string) {
    let data = "";
    // nanosecond unix time
    let timestamp = new Date().getTime() * 1000 * 1000;

    for (const key of Array.from(stats.keys())) {
      data +=
        this.concatCamelCase("replay", key) +
        ",testSet=" +
        testSet +
        " value=" +
        stats.get(key) +
        " " +
        timestamp +
        "\n";
    }
    logger.info("stats: " + data);

    return new RestClient().post(this.influxdbUrl, null, data);
  }

  /**
   * Concatenate two camel case words
   *
   * The first letter of the second word is converted to upper case.
   *
   * @param w1
   * @param w2
   */
  concatCamelCase(w1: string, w2: string) {
    return w1 + w2.charAt(0).toUpperCase() + w2.slice(1);
  }
}

if (require.main === module) {
  new ReplayServer();
}
