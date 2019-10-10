import { Logger, RestClient } from "chipster-nodejs-core";
import { empty, interval, of } from "rxjs";
import { catchError, mergeMap } from "rxjs/operators";
import { VError } from "verror";
import ReplaySession from "./replay-session";

const ArgumentParser = require("argparse").ArgumentParser;
const logger = Logger.getLogger(__filename, "logs/chipster.log");
const schedule = require("node-schedule");
const serveIndex = require("serve-index");
const express = require("express");
const path = require("path");
const v8 = require("v8");

export default class ReplayServer {
  resultsPath: string;
  influxdbUrl: string;
  restClient: RestClient;

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
      defaultValue: "results"
    });
    parser.addArgument(["--temp", "-t"], {
      help: "temp directory",
      defaultValue: ReplaySession.tempDefault
    });
    parser.addArgument(["--schedule", "-s"], {
      help:
        "how often to run a test sets, in format CRON_SCHEDULE:FILTER1[ FILTER2...][:PARALLEL_JOBS][:JOB_TIMEOUT_SECONDS]. Run immediately If CRON_SCHEDULE is empty. Filter is a prefix of the session name or a special string 'example-sessions'. ",
      action: "append"
    });
    parser.addArgument(["--influxdb", "-i"], {
      help: "influxdb url for statistics, e.g. http://influxdb:8086/write?db=db"
    });
    parser.addArgument(["--port", "-P"], {
      help: "HTTP port for serving the result files",
      defaultValue: "9000"
    });

    let args = parser.parseArgs();

    logger.info(
      "start server for sharing the result files, in port",
      args.port
    );

    ReplaySession.mkdirIfMissing(args.results);
    ReplaySession.mkdirIfMissing(args.temp);

    // start a web server for the results
    var app = express();
    app.use(
      "/",
      express.static("results"),
      serveIndex("results", { icons: true, view: "details" })
    );
    app.listen(parseInt(args.port));

    let schedules = args.schedule;
    if (schedule.length === 0) {
      schedules = [":"];
    }

    // RestClient contains the connection pool for HTTP keep-alive, so everything should use the
    // same instance
    this.restClient = new RestClient(true, null, null);

    schedules.forEach((sched: string) => {
      const colonSplitted = sched.split(":");
      let cron = colonSplitted[0];
      const testSets = colonSplitted[1].split(" ");
      let parallel = 1;
      if (colonSplitted.length >= 3 && colonSplitted[2].length > 0) {
        parallel = parseInt(colonSplitted[2]);
      }
      let jobTimeout = 60 * 60;
      if (colonSplitted.length >= 4 && colonSplitted[3].length > 0) {
        jobTimeout = parseInt(colonSplitted[3]);
      }
      logger.info("run " + parallel + " jobs in parallel");
      logger.info("cancel jobs after " + jobTimeout + " seconds");

      const filters = testSets.map(f => {
        if (f === "example-sessions") {
          return f;
        }
        // match only full folder names
        return f + "/";
      });
      const testSetName = testSets.join("_");

      const replayNow = () => {
        new ReplaySession()
          .replay(
            args.URL,
            args.username,
            args.password,
            false,
            parallel,
            true,
            path.join(args.results, testSetName),
            path.join(args.temp, testSetName),
            filters,
            null,
            jobTimeout
          )
          .pipe(
            mergeMap((stats: Map<string, number>) => {
              return this.postToInflux(
                stats,
                testSetName,
                args.influxdb,
                this.restClient
              );
            })
          )
          .subscribe(
            () => logger.info("session replay " + testSetName + " done"),
            err =>
              logger.error(
                new VError(err, "session replay " + testSetName + " error")
              ),
            () => logger.info("session replay " + testSetName + " completed")
          );
      };

      if (cron.length === 0) {
        replayNow();
      } else {
        logger.info("schedule session replay test " + filters + " at " + cron);
        const replaySessionJob = schedule.scheduleJob(cron, () => {
          replayNow();
        });
        logger.info("next invocation " + replaySessionJob.nextInvocation());
      }

      interval(30 * 1000)
        .pipe(
          mergeMap(() => {
            const stats = new Map();
            stats.set("memExternal", process.memoryUsage().external);
            stats.set("memHeapTotal", process.memoryUsage().heapTotal);
            stats.set("memHeapUsed", process.memoryUsage().heapUsed);
            stats.set("memRss", process.memoryUsage().rss);
            stats.set(
              "memHeapTotalAvailable",
              v8.getHeapStatistics().total_available_size
            );
            return this.postToInflux(
              stats,
              "memoryUsage",
              args.influxdb,
              this.restClient
            ).pipe(
              catchError(err => {
                if (err.statusCode === 500) {
                  // this happens a lot, no need to log the stack
                  logger.error(
                    "posting memory statistics to influx failed: " +
                      err.statusCode +
                      " " +
                      err.message
                  );
                } else {
                  logger.error(new VError(err, "memory monitoring error"));
                }
                // allow timer to continue even if posting fails every now and then
                return empty();
              })
            );
          })
        )
        .subscribe();
    });

    // these shouldn't happen if RestClient handled errrors correctly
    // apparently it doesn't, so let's catch them to prevent crashing this server after each tool failure
    process.on("uncaughtException", err => {
      logger.error(new VError(err, "uncaught exception"));
    });

    // try to see if the process was killed with SIGINT
    process.on("SIGINT", function() {
      console.log("SIGINT");
      logger.info("SIGINT");
      if (this.restClient) {
        this.restClient.destroy();
      }
      process.exit();
    });
  }

  postToInflux(
    stats: Map<string, number>,
    testSet: string,
    influxdb: string,
    restClient: RestClient
  ) {
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

    logger.debug("stats: " + data);

    if (influxdb != null) {
      logger.debug("post to InfluxDB " + testSet + " " + influxdb);
      return restClient.post(influxdb, null, data);
    } else {
      return of(null);
    }
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
