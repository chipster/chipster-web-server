import { Dataset, Job, Session } from "chipster-js-common";
import { Logger, RestClient, HttpResponse } from "chipster-nodejs-core";
import { empty, from, of, range, timer } from "rxjs";
import {
  catchError,
  concatAll,
  map,
  mergeMap,
  takeUntil,
  tap,
  toArray,
} from "rxjs/operators";
import ChipsterUtils from "./chipster-utils.js";
import { fileURLToPath } from "url";

const ArgumentParser = require("argparse").ArgumentParser;
const logger = Logger.getLogger(fileURLToPath(import.meta.url));

export default class Benchmark {
  restClient: RestClient;
  sessionPrefix = "benchmark_session_";
  sessionIds: string[] = [];
  sessionIdsWithoutMetadata: string[] = [];
  datasetIds: Map<string, string> = new Map();
  datasetIdsWithoutMetadata: Map<string, string> = new Map();
  jobIds: Map<string, string> = new Map();

  readonly notes =
    "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do " +
    "eiusmod tempor incididunt ut labore et dolore magna aliqua.Ut enim ad minim " +
    "veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo " +
    "consequat.Duis aute irure dolor in reprehenderit in voluptate velit esse cillum " +
    "dolore eu fugiat nulla pariatur.Excepteur sint occaecat cupidatat non proident, " +
    "sunt in culpa qui officia deserunt mollit anim id est laborum.\n";

  readonly screenOutput =
    'print("Hello World!")\n[1] "Hello World!"\n\n'.repeat(100);

  influxUrl;
  results = "";

  onlyPost: boolean;

  maxRequestCount: number;
  maxTime: number;

  constructor() {
    this.parseCommand();
  }

  parseCommand() {
    const version = "Chipster server benchmark version 0.2.0";

    const parser = new ArgumentParser({
      add_help: true,
      description: "Chipster server benchmark",
    });

    parser.add_argument("-v", "--version", {
      action: "version",
      version: version,
      help: "show program's version nubmer and exit",
    });

    parser.add_argument("URL", { help: "url of the app server" });
    parser.add_argument("--username", "-u", {
      help: "username for the Chipster server",
    });
    parser.add_argument("--password", "-p", {
      help: "password for the Chipster server",
    });
    parser.add_argument("--influx", { help: "url of the influxdb" });
    parser.add_argument("--debug", "-d", {
      help: "do not delete the test session",
      action: "store_true",
    });
    parser.add_argument("--quiet", "-q", {
      help: "do not print job state changes",
      action: "store_true",
    });
    parser.add_argument("--post", {
      help: "only create db rows to allow tests with larger databases",
      action: "store_true",
    });
    parser.add_argument("--requests", "-r", {
      help: "max number of requests for each test",
      default: 100,
    });
    parser.add_argument("--time", "-t", {
      help: "max test duration in milliseconds",
      default: 1000,
    });

    const args = parser.parse_args();

    this.onlyPost = args.post;
    this.maxRequestCount = args.requests;
    this.maxTime = args.time;
    this.influxUrl = args.influx;

    console.log("login as", args.username);

    this.restClient = new RestClient(true);

    let benchmark = ChipsterUtils.getToken(
      args.URL,
      args.username,
      args.password,
      this.restClient,
    ).pipe(
      mergeMap((token: string) =>
        ChipsterUtils.configureRestClient(args.URL, token, this.restClient),
      ),
    );

    if (this.onlyPost) {
      benchmark = benchmark.pipe(
        mergeMap(() =>
          this.measure("post session                            ", (i) =>
            this.postEmptySession(i, this.sessionIds),
          ),
        ),
        mergeMap(() =>
          this.measure("post dataset                            ", (i) =>
            this.postDataset(i, 100, this.datasetIds, this.sessionIds),
          ),
        ),
        mergeMap(() =>
          this.measure("post job                                ", (i) =>
            this.postJob(i),
          ),
        ),
      );
    } else {
      benchmark = benchmark.pipe(
        mergeMap(() => this.deleteOldSessions(this.sessionPrefix)),
        mergeMap((s) => this.restClient.getSessions()),
        tap((s: Session[]) => {
          if (s.length > 0) {
            logger.warn(
              "account is not empty, results may be lower:",
              s.length,
              "session(s)",
            );
          }
        }),
        mergeMap(() =>
          this.measure("get static                              ", (i) =>
            this.getStatic(i),
          ),
        ),
        mergeMap(() =>
          this.measure("post session                            ", (i) =>
            this.postEmptySession(i, this.sessionIds),
          ),
        ),
        mergeMap(() =>
          this.measure("post session                            ", (i) =>
            this.postEmptySession(i, this.sessionIdsWithoutMetadata),
          ),
        ),
        mergeMap(() =>
          this.measure("get sessions by id                      ", (i) =>
            this.getSessionById(i),
          ),
        ),
        mergeMap(() =>
          this.measure("post dataset                            ", (i) =>
            this.postDataset(i, 100, this.datasetIds, this.sessionIds),
          ),
        ),
        mergeMap(() =>
          this.measure("post dataset without metadata           ", (i) =>
            this.postDataset(
              i,
              0,
              this.datasetIdsWithoutMetadata,
              this.sessionIdsWithoutMetadata,
            ),
          ),
        ),
        mergeMap(() =>
          this.measure("get dataset                             ", (i) =>
            this.getDataset(i, this.datasetIds),
          ),
        ),
        mergeMap(() =>
          this.measure("get dataset without metadata            ", (i) =>
            this.getDataset(i, this.datasetIdsWithoutMetadata),
          ),
        ),
        mergeMap(() =>
          this.measure("get datasets by session                 ", (i) =>
            this.getDatasetsBySession(i, this.datasetIds),
          ),
        ),
        mergeMap(() =>
          this.measure("get datasets by session without metadata", (i) =>
            this.getDatasetsBySession(i, this.datasetIdsWithoutMetadata),
          ),
        ),
        mergeMap(() =>
          this.measure("put and get dataset                     ", (i) =>
            this.getAndPutDataset(i, this.datasetIds),
          ),
        ),
        mergeMap(() =>
          this.measure("put and get dataset without metadata    ", (i) =>
            this.getAndPutDataset(i, this.datasetIdsWithoutMetadata),
          ),
        ),
        mergeMap(() =>
          this.measure("post job                                ", (i) =>
            this.postJob(i),
          ),
        ),
        mergeMap(() =>
          this.measure("get job                                 ", (i) =>
            this.getJob(i),
          ),
        ),
        mergeMap(() =>
          this.measure("get jobs by session                     ", (i) =>
            this.getJobsBySession(i),
          ),
        ),
        mergeMap(() =>
          this.measure("put and get job                         ", (i) =>
            this.getAndPutJob(i),
          ),
        ),
        mergeMap(() =>
          this.measureOnce(
            "delete job                              ",
            this.deleteJob(),
          ),
        ),
        mergeMap(() =>
          this.measureOnce(
            "delete dataset                          ",
            this.deleteDataset(this.datasetIds),
          ),
        ),
        mergeMap(() =>
          this.measureOnce(
            "delete dataset without metadata         ",
            this.deleteDataset(this.datasetIdsWithoutMetadata),
          ),
        ),
        // measure this only after half of the sessions are deleted already
        mergeMap(() =>
          this.measure("get sessions by username                ", (i) =>
            this.getSessionsByUsername(i),
          ),
        ),
        mergeMap(() =>
          this.measureOnce(
            "delete session                          ",
            this.deleteSession(this.sessionIds),
          ),
        ),
        mergeMap(() =>
          this.measureOnce(
            "delete session                          ",
            this.deleteSession(this.sessionIdsWithoutMetadata),
          ),
        ),
        mergeMap(() => this.postResults()),
      );
    }
    benchmark.subscribe(
      () => console.log("chipster benchmark done"),
      (err) => console.error("chipster benchmark error", err),
      () => console.log("chipster benchmark completed"),
    );
  }

  postResults() {
    logger.info(this.results);
    if (this.influxUrl) {
      return this.restClient.post(this.influxUrl, null, this.results);
    }
    logger.warn("influxdb url is not configured");
    return empty();
  }

  measureOnce(name, job$) {
    const t = +new Date();
    return job$.pipe(
      tap((requestCount: number) => {
        const duration = +new Date() - t;
        const rps = (requestCount * 1000) / duration;
        this.addResult(name, 1, rps);
        logger.info(name, rps, "\trequest/s (sequential)");
      }),
    );
  }

  measure(name, jobFunction) {
    let loops;

    if (this.onlyPost) {
      loops = [empty(), this.loop(jobFunction, 16, name)];
    } else {
      loops = [
        this.loop(jobFunction, 1, name),
        this.loop(jobFunction, 4, name),
      ];
    }

    return of(...loops).pipe(
      concatAll(),
      toArray(),
      tap((res) => {
        logger.info(
          name,
          res[0],
          "\trequest/s (sequential), \t",
          res[1],
          "\trequests/s (parallel)",
        );
      }),
    );
  }

  addResult(name, threads, value) {
    const key = name.trim().split(" ").join("-");
    const tags = "threads=" + threads;
    this.results +=
      key +
      "," +
      tags +
      " value=" +
      value +
      " " +
      new Date().getTime() * 1000 * 1000 +
      "\n";
  }

  loop(jobFunction, threads, name) {
    let t: number;
    return of(null).pipe(
      tap(() => (t = +new Date())),
      mergeMap(() => range(0, this.maxRequestCount)),
      mergeMap(jobFunction, null, threads),
      takeUntil(timer(this.maxTime)),
      toArray(),
      map((array) => array.length),
      map((requestCount: number) => {
        const duration = +new Date() - t;
        const throughput = (requestCount * 1000) / duration;
        this.addResult(name, threads, throughput);

        return throughput;
      }),
      catchError((err) => {
        logger.error("error in", name, err);
        return of(-1);
      }),
    );
  }

  postEmptySession(i: number, sessionIds: string[]) {
    return of(i).pipe(
      map((i) => {
        const s = {
          name: this.sessionPrefix + i,
          notes: this.notes,
        };
        return s;
      }),
      mergeMap((s) => this.restClient.postSession(s)),
      tap((sessionId: string) => sessionIds.push(sessionId)),
    );
  }

  getStatic(i: number) {
    return of(i).pipe(
      mergeMap(() => this.restClient.getSessionDbUri()),
      mergeMap((uri: string) => this.restClient.request("GET", uri)),
      tap((resp: HttpResponse) => {
        if (resp.response.statusCode != 404) {
          throw this.restClient.reponseToError(resp);
        }
      }),
    );
  }

  postDataset(
    i: number,
    metadataCount: number,
    idMap: Map<string, string>,
    sessionIds: string[],
  ) {
    // about 100 datasets per session
    const sessionId =
      sessionIds[Math.floor((Math.random() * sessionIds.length) / 100 + 1)];
    return of(i).pipe(
      map((i) => {
        const dataset: Dataset = {
          name: "dataset_" + i,
          fileId: null,
          checksum: null,
          created: null,
          datasetId: null,
          typeTags: null,
          notes: null,
          size: 0,
          sourceJob: null,
          x: null,
          y: null,
          sessionId: null,
          metadataFiles: [],
          state: null,
        };
        let phenodata = "";
        for (let j = 0; j < metadataCount; j++) {
          phenodata += "metadatakey_" + j + "\t" + "value_" + j + "\n";
        }
        dataset.metadataFiles.push({
          name: "phenodata",
          content: phenodata,
        });
        dataset.fileId = ChipsterUtils.uuidv4();
        dataset.notes = this.notes;

        return dataset;
      }),
      mergeMap((dataset) => this.restClient.postDataset(sessionId, dataset)),
      tap((id: string) => idMap.set(id, sessionId)),
    );
  }

  getDataset(i: number, datasetIds: Map<string, string>) {
    const datasetId = Array.from(datasetIds.keys())[i % datasetIds.size];
    return of(i).pipe(
      mergeMap((s) =>
        this.restClient.getDataset(datasetIds.get(datasetId), datasetId),
      ),
    );
  }

  getDatasetsBySession(i: number, datasetIds: Map<string, string>) {
    const datasetSessions = Array.from(datasetIds.values());
    const sessionId =
      datasetSessions[Math.floor(Math.random() * datasetSessions.length)];
    return of(i).pipe(mergeMap((s) => this.restClient.getDatasets(sessionId)));
  }

  getAndPutDataset(i: number, datasetIds: Map<string, string>) {
    return this.getDataset(i, datasetIds).pipe(
      mergeMap((d: Dataset) => {
        d.notes = d.notes + "-";
        return this.restClient.putDataset(datasetIds.get(d.datasetId), d);
      }),
    );
  }

  deleteDataset(datasetIds: Map<string, string>) {
    return from(datasetIds.keys()).pipe(
      mergeMap(
        (id) => this.restClient.deleteDataset(datasetIds.get(id), id),
        null,
        1,
      ),
      toArray(),
      map(() => datasetIds.size),
    );
  }

  postJob(i: number) {
    // select a random dataset
    const datasetId = Array.from(this.datasetIds.keys())[
      Math.floor(Math.random() * this.datasetIds.size)
    ];
    // job must be in the same session
    const sessionId = this.datasetIds.get(datasetId);
    return of(i).pipe(
      map((i) => {
        const job = {
          inputs: [],
          module: "misc",
          parameters: [],
          screenOutput: "",
          state: "COMPLETED",
          stateDetail: "",
          toolCategory: "Utilitites",
          toolId: "benchmark-tool.py",
          toolName: "Fake tool",
        };
        for (let j = 0; j < 10; j++) {
          job.inputs.push({
            inputId: "input" + j,
            datasetId: datasetId,
          });

          job.parameters.push({
            parameterId: "parameter" + j,
            type: "STRING",
            value: "value" + j,
          });
        }
        job.screenOutput = this.screenOutput;

        return job;
      }),
      mergeMap((job) => this.restClient.postJob(sessionId, job)),
      tap((id: string) => this.jobIds.set(id, sessionId)),
    );
  }

  getJob(i: number) {
    const jobId = Array.from(this.jobIds.keys())[i % this.jobIds.size];
    return of(i).pipe(
      mergeMap((s) => this.restClient.getJob(this.jobIds.get(jobId), jobId)),
    );
  }

  getJobsBySession(i: number) {
    const jobSessions = Array.from(this.jobIds.values());
    const sessionId =
      jobSessions[Math.floor(Math.random() * jobSessions.length)];
    return of(i).pipe(mergeMap((s) => this.restClient.getDatasets(sessionId)));
  }

  getAndPutJob(i: number) {
    return this.getJob(i).pipe(
      mergeMap((j: Job) => {
        j.screenOutput = j.screenOutput + "-";
        return this.restClient.putJob(this.jobIds.get(j.jobId), j);
      }),
    );
  }

  deleteJob() {
    return from(this.jobIds.keys()).pipe(
      mergeMap(
        (id) => this.restClient.deleteJob(this.jobIds.get(id), id),
        null,
        1,
      ),
      toArray(),
      map(() => this.jobIds.size),
    );
  }

  getSessionsByUsername(i: number) {
    return of(i).pipe(mergeMap((s) => this.restClient.getSessions()));
  }

  getSessionById(i: number) {
    const randomId =
      this.sessionIds[Math.floor(Math.random() * this.sessionIds.length)];
    return of(i).pipe(mergeMap((s) => this.restClient.getSession(randomId)));
  }

  deleteSession(sessionIds: string[]) {
    return from(sessionIds).pipe(
      mergeMap((id) => this.restClient.deleteSession(id), null, 1),
      toArray(),
      map(() => sessionIds.length),
    );
  }

  deleteOldSessions(nameStart) {
    return this.restClient.getSessions().pipe(
      map((sessions: Session[]) => {
        return sessions.filter((s) => {
          return s.name.startsWith(nameStart);
        });
      }),
      mergeMap((sessions: Session[]) => from(sessions)),
      mergeMap(
        (session: Session) => {
          // console.log('delete session', session.name);
          return this.restClient.deleteSession(session.sessionId);
        },
        null,
        1,
      ),
      toArray(),
      tap((array: any[]) => {
        if (array.length > 0) {
          logger.warn(
            "found and deleted",
            array.length,
            "old benchmark session(s)",
          );
        }
      }),
    );
  }

  millisecondsToHumanReadable(ms: number): string {
    const hours = ms / (1000 * 60 * 60);
    const intHours = Math.floor(hours);

    // get remainder from hours and convert to minutes
    const minutes = (hours - intHours) * 60;
    const intMinutes = Math.floor(minutes);

    // get remainder from minutes and convert to seconds
    const seconds = (minutes - intMinutes) * 60;
    const intSeconds = Math.floor(seconds);

    if (intHours > 0) {
      return intHours + "h " + intMinutes + "m";
    } else if (intMinutes > 0) {
      return intMinutes + "m " + intSeconds + "s";
    } else {
      return intSeconds + "s";
    }
  }
}

if (import.meta.url.endsWith(process.argv[1])) {
  new Benchmark();
}
