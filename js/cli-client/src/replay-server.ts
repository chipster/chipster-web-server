import { Logger, RestClient } from "chipster-nodejs-core";
import { empty, interval, of } from "rxjs";
import { catchError, mergeMap } from "rxjs/operators";
import { VError } from "verror";
import ReplaySession from "./replay-session";
import schedule = require("node-schedule");
import serveIndex = require("serve-index");
import express = require("express");
import path = require("path");
import v8 = require("v8");
import morgan = require("morgan");

const fs = require("fs");
const rfs = require ("rotating-file-stream");
const ArgumentParser = require("argparse").ArgumentParser;
const logger = Logger.getLogger(__filename, "logs/chipster.log");

export default class ReplayServer {
  resultsPath: string;
  influxdbUrl: string;
  restClient: RestClient;

  constructor() {
    Logger.addLogFile();
    this.parseCommand();
  }

  parseCommand(): void {
    const parser = new ArgumentParser({
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
    parser.addArgument(["--resultsRoot"], {
      help: "root directory for results"
    });
    parser.addArgument(["--resultName"], {
      help: "name for the result directory, goes under resultsRoot"
    });
    parser.addArgument(["--skipFilterAsResultName"], {
      help: "don't use the filter string as the name of the result dir",
      action: "storeTrue"
    });
    parser.addArgument(["--tempRoot", "-t"], {
      help: "root directory for temp files"
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

    const args = parser.parseArgs();

    const resultsRoot =
      args.resultsRoot != null
        ? args.resultsRoot
        : ReplaySession.resultsRootDefault;

    logger.info(
      "start server for sharing the result files in port " +
        args.port +
        ", results root is",
      resultsRoot
    );

    fs.mkdirSync(resultsRoot, {recursive:true})

    const accessLogStream = rfs("access.log", {
      interval: "1d", // rotate daily
      path: path.join("logs"),
      maxFiles: 60
    });

    // add custom token to log all request headers
    morgan.token("request-headers", function(req, res) {
      return Object.keys(req.headers);
    });

    const shortWithDateFormat =
      "[:date[iso]] :req[x-forwarded-for] :remote-addr :remote-user :method :url HTTP/:http-version :status :res[content-length] - :response-time ms :request-headers";

    // start a web server for the results
    const app = express();

    // log requests in case the server crashes
    app.use(
      morgan(shortWithDateFormat, {
        immediate: true,
        stream: accessLogStream,
        skip: (req, res) => req.url === "/alive"
      })
    );

    // log responses
    app.use(
      morgan(shortWithDateFormat, {
        immediate: false,
        stream: accessLogStream,
        skip: (req, res) => req.url === "/alive"
      })
    );

    // errors to stdout
    app.use(
      morgan(shortWithDateFormat, {
        immediate: false,
        skip: (req, res) => {
          return res.statusCode < 400;
        }
      })
    );

    app.get("/alive", (req, res) => {
      res.send("ok\n");
    });

    app.use(
      "/",
      express.static(resultsRoot),
      serveIndex(resultsRoot, { icons: true, view: "details" })
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
      const cron = colonSplitted[0];
      const testSets = colonSplitted[1].split(" ");
      let parallel = 1;
      if (colonSplitted.length >= 3 && colonSplitted[2].length > 0) {
        parallel = parseInt(colonSplitted[2]);
      }
      let jobTimeout = 60 * 60;
      if (colonSplitted.length >= 4 && colonSplitted[3].length > 0) {
        jobTimeout = parseInt(colonSplitted[3]);
      }

      const filters = testSets.map(f => {
        if (f === "example-sessions") {
          return f;
        }
        // match only full folder names
        return f + "/";
      });
      const testSetName = testSets.join("_");

      // figure out the result name, i.e. the dir under resultsRoot where the results go
      let resultName;
      if (args.resultName != null) {
        resultName = args.resultName;
      } else if (!args.skipFilterAsResultName) {
        resultName = testSetName;
      }

      const replayNow = () => {
        new ReplaySession()
          .replayFilter(args.URL, args.username, args.password, filters, {
            parallel: parallel,
            quiet: true,
            resultsRoot: args.resultsRoot,
            resultName: resultName,
            tempRoot: args.tempRoot,
            jobTimeout: jobTimeout
          })
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
        logger.info(
          "schedule " +
            filters +
            " at " +
            cron +
            ", parallel: " +
            parallel +
            ", timeout: " +
            jobTimeout +
            "s"
        );
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
    const timestamp = new Date().getTime() * 1000 * 1000;

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
  concatCamelCase(w1: string, w2: string): string {
    return w1 + w2.charAt(0).toUpperCase() + w2.slice(1);
  }
}

if (require.main === module) {
  new ReplayServer();
}
