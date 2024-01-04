import { Dataset, Job, Module, PhenodataUtils, Session, Tool } from "chipster-js-common";
import MetadataFile from "chipster-js-common/lib/model/metadata-file";
import { Logger, RestClient } from "chipster-nodejs-core";
import * as _ from "lodash";
import { concat, empty, forkJoin, from, ObjectUnsubscribedError, Observable, of, Subject, throwError, timer } from "rxjs";
import { last } from "rxjs/internal/operators/last";
import { catchError, finalize, map, merge, mergeMap, takeUntil, tap, toArray } from "rxjs/operators";
import { VError } from "verror";
import ChipsterUtils, { missingInputError } from "./chipster-utils";
import WsClient from "./ws-client";
const humanizeDuration = require("humanize-duration");

const ArgumentParser = require("argparse").ArgumentParser;
import fs = require("fs");
import path = require("path");

const logger = Logger.getLogger(__filename);

interface ReplayOptions {
  debug?: boolean;
  parallel?: number;
  quiet?: boolean;
  resultsRoot?: string;
  resultName?: string;
  tempRoot?: string;
  jobTimeout?: number;
}

/**
 * Session replay test
 *
 * Replay the jobs in a session to test that the tools still work.
 *
 * The original sessions can be local zip files (given as parameter "session") or a
 * server session (selected with parameter --filter). Zip files are uploaded and
 * extracted which allows us to investigate it with the Rest API.
 *
 * We create a new empty session for the test jobs.
 * We copy (by downloading and uploading) the input files of each job from the original
 * (uploaded) session to this new session and replay the jobs using the same inputs and
 * parameters. Phenodata is copied from the original dataset and there is basic support for
 * multi-inputs.
 *
 * One session can have multiple jobs and all of them are run, but the outputs
 * of the previous jobs are not used for the inputs, but the input files are copied always
 * from the original session. This allows better parallelization, but also prevents the test
 * from noticing if the new outputs are incompatible for the later tools in the workflow.
 */
export default class ReplaySession {
  readonly flagFile = "test-ok";
  readonly toolNotFoundError = "ToolNotFoundError";

  static readonly ignoreJobIds = [
    "operation-definition-id-import",
    "operation-definition-id-user-modification",
    "fi.csc.chipster.tools.ngs.LocalNGSPreprocess.java"
  ];

  static readonly tempRootDefault = "tmp";
  static readonly resultsRootDefault = "results";
  static readonly parallelDefault = 1;
  static readonly quietDefault = false;
  static readonly debugDefault = false;
  static readonly jobTimeoutDefault = 600;

  startTime: Date;
  restClient: any;
  resultsPath: string;
  uploadSessionPrefix: string;
  replaySessionPrefix: string;
  tempPath: string;
  stats = new Map<string, number>();

  parseCommand(): void {
    const version = "Chipster session replay test version 0.1.0";

    const parser = new ArgumentParser({
      add_help: true,
      description: "Chipster session replay test"
    });

    parser.add_argument( '-v', '--version' , { action: 'version', version: version, help: 'show program\'s version number and exit' })

    parser.add_argument("URL", { help: "url of the app server" });
    parser.add_argument("--username", "-u", {
      help: "username for the server"
    });
    parser.add_argument("--password", "-p", {
      help: "password for the server"
    });
    parser.add_argument("--debug", "-d", {
      help: "do not delete the test session",
      action: "store_true"
    });
    parser.add_argument("--parallel", "-P", {
      help: "how many jobs to run in parallel (>1 implies --quiet)"
    });
    parser.add_argument("--jobTimeout", "-J", {
      help: "cancel job if it takes longer than this, in seconds"
    });
    parser.add_argument("--quiet", "-q", {
      help: "do not print job state changes",
      action: "store_true"
    });
    parser.add_argument("--resultsRoot", {
      help: "root directory for results"
    });
    parser.add_argument("--resultName", {
      help: "name for the result directory, goes under resultsRoot"
    });
    parser.add_argument("--tempRoot", "-t", {
      help: "root directory for temp files"
    });
    parser.add_argument("--filter", "-F", {
      help:
        "replay all sessions stored on the server starting with this string",
      action: "append"
    });
    parser.add_argument("session", {
      help: "session file or dir to replay",
      nargs: "?"
    });

    const args = parser.parse_args();

    // check that user gives either session or filter
    if (args.filter == null && args.session == null) {
      console.log("Specify session or use --filter parameter");
      return;
    } else if (args.filter != null && args.session != null) {
      console.log(
        "Specify session or use --filter parameter, using both at the same time not supported yet"
      );
      return;
    }

    // local session or remote session with filter

    const options = {
      debug: args.debug,
      parallel: args.parallel != null ? parseInt(args.parallel) : null,
      quiet: args.quiet,
      resultsRoot: args.resultsRoot,
      resultName: args.resultName,
      tempRoot: args.tempRoot,
      jobTimeout: args.jobTimeout != null ? parseInt(args.jobTimeout) : null
    };

    const replay$ =
      args.filter != null
        ? this.replayFilter(
            args.URL,
            args.username,
            args.password,
            args.filter,
            options
          )
        : this.replayLocalSession(
            args.URL,
            args.username,
            args.password,
            args.session,
            options
          );

    // start replay
    replay$.subscribe(
      () => logger.info("session replay done"),
      err => logger.error(new VError(err, "session replay error")),
      // () => logger.info("session replay completed")
    );
  }

  replayLocalSession(
    url: string,
    username: string,
    password: string,
    sessionPath: string,
    options?: ReplayOptions
  ) {
    return this.replay(url, username, password, null, sessionPath, options);
  }

  replayFilter(
    url: string,
    username: string,
    password: string,
    filter: string[],
    options?: ReplayOptions
  ) {
    return this.replay(url, username, password, filter, null, options);
  }

  replay(
    URL: string,
    username: string,
    password: string,
    filter: string[],
    sessionPath: string,
    options?: ReplayOptions
  ) {
    const parallel =
      options != null && options.parallel != null
        ? options.parallel
        : ReplaySession.parallelDefault;
    const quiet =
      (options != null && options.quiet != null
        ? options.quiet
        : ReplaySession.quietDefault) || parallel > 1;
    const jobTimeout =
      options != null && options.jobTimeout != null
        ? options.jobTimeout
        : ReplaySession.jobTimeoutDefault;
    const debug =
      options != null && options.debug != null
        ? options.debug
        : ReplaySession.debugDefault;
    const resultsRoot =
      options != null && options.resultsRoot != null
        ? options.resultsRoot
        : ReplaySession.resultsRootDefault;

    let testSet: string;
    if (filter) {
      testSet = this.filterArrayToStringfilter(filter);
    } else {
      testSet = path.basename(sessionPath);
    }
    const resultsPath =
      options != null && options.resultName != null
        ? resultsRoot + path.sep + options.resultName
        : resultsRoot + path.sep + testSet;
    this.resultsPath = resultsPath;

    const tempRoot =
      options != null && options.tempRoot != null
        ? options.tempRoot
        : ReplaySession.tempRootDefault;

    const tempPath = tempRoot + path.sep + testSet;
    this.tempPath = tempPath;

    this.startTime = new Date();


    /* Session prefixes for recognizing old sessions produced by this job

    Failing cronjobs create easily lots of sessions. We want to delete all old sessions
    created by this cronjob or test set, but don't want to disturb other test sets that
    might be running at the same time. Most likely the user uses different result directories for
    different test sets, so it's a good value for grouping these temporary sessions too.
    */
    logger.info(
      "start replay test " +
        testSet +
        ", server " +
        URL +
        ", resultsPath " +
        resultsPath +
        ", tempPath " +
        tempPath +
        ", parallel jobs " +
        parallel +
        ", jobTimeout " +
        jobTimeout
    );


    this.uploadSessionPrefix = "zip-upload/" + testSet + "/";
    this.replaySessionPrefix = "replay/" + testSet + "/";

    if (isNaN(parallel)) {
      throw new Error(
        'the parameter "parallel" is not an integer: ' + parallel
      );
    }

    if (this.resultsPath.length === 0) {
      throw new Error("results path is not set");
    }

    // set recursive to tolerate existing dir
    fs.mkdirSync(this.resultsPath, { recursive: true });

    const importErrors: ImportError[] = [];
    let results = [];
    const tempSessionsToDelete: Set<string> = new Set();

    const fileSessions$ = of(null).pipe(
      mergeMap(() => {
        if (sessionPath != null) {
          return from(this.getSessionFiles(sessionPath));
        } else {
          return empty();
        }
      }),
      mergeMap(
        (s: string) => {
          return this.uploadSession(s, quiet).pipe(
            tap((session: Session) => tempSessionsToDelete.add(session.sessionId)),
            catchError(err => {
              // unexpected technical problems
              logger.error(new VError(err, "session import error"));
              importErrors.push({
                file: s,
                error: err
              });
              return of([]);
            })
          );
        },
        null,
        parallel
      )
    );

    const serverSessions$ = of(null).pipe(
      mergeMap(() => this.restClient.getSessions()),
      map((sessions: Session[]) => {
        const filtered = [];
        if (filter && sessions) {
          filter
            .filter(f => !f.startsWith("example-sessions"))
            .forEach(prefix => {
              sessions.forEach(s => {
                if (s.name.startsWith(prefix)) {
                  filtered.push(s);
                }
              });
            });
        }
        return filtered;
      }),
      mergeMap((sessions: Session[]) => from(sessions)),
    );

    const exampleSessions$ = of(null).pipe(
      mergeMap(() => {
        if (filter?.includes("example-sessions")) {
          return this.restClient.getExampleSessions("chipster");
        } else {
          return of([]);
        }
      }),
      mergeMap((sessions: Session[]) => {
        return from(sessions);
      }),
    );

    let jobPlanCount = 0;
    let allTools: Module[];

    logger.info("login as " + username);
    this.restClient = new RestClient(true, null, null);
    return ChipsterUtils.getToken(
      URL,
      username,
      password,
      this.restClient
    ).pipe(
      mergeMap((token: string) =>
        ChipsterUtils.configureRestClient(URL, token, this.restClient)
      ),
      mergeMap(() => this.restClient.getTools()),
      tap(
        (tools: Module[]) =>
          (allTools = tools.filter(m => m.name !== "Kielipankki"))
      ),
      mergeMap(() =>
        this.deleteOldSessions(
          this.uploadSessionPrefix,
          this.replaySessionPrefix
        )
      ),
      mergeMap(() => this.writeResults([], [], false, null, testSet, allTools)),
      mergeMap(() =>
        fileSessions$.pipe(
          merge(serverSessions$),
          merge(exampleSessions$)
        )
      ),
      mergeMap((session: Session) => this.getSessionJobPlans(session, quiet, allTools)),
      tap((jobPlans: JobPlan[]) => {
        jobPlanCount += jobPlans.length;
      }),
      finalize(() =>
        logger.info("test set " + testSet + " has " + jobPlanCount + " jobs")
      ),
      mergeMap((jobPlans: JobPlan[]) => from(jobPlans)),
      mergeMap(
        (plan: JobPlan) => {
          logger.info(
            testSet +
              " " +
              plan.originalSession.name +
              " run tool " +
              plan.job.toolId
          );
          tempSessionsToDelete.add(plan.replaySessionId);
          return this.replayJob(plan, quiet, testSet, jobTimeout);
        },
        null,
        parallel
      ),
      tap((replayResult: ReplayResult) => {
        results = results.concat(replayResult);
        logger.info(
          testSet +
            " (" +
            results.length +
            "/" +
            jobPlanCount +
            ")" +
            " " +
            replayResult.sessionName +
            " finished tool " +
            replayResult.job.toolId
        );
      }),
      // update results after each job
      mergeMap(() =>
        this.writeResults(
          results,
          importErrors,
          false,
          jobPlanCount,
          testSet,
          allTools
        )
      ),
      toArray(), // wait for completion and write the final results
      mergeMap(() =>
        this.writeResults(
          results,
          importErrors,
          true,
          jobPlanCount,
          testSet,
          allTools
        )
      ),
      
      mergeMap(() => {
        // TODO should be done after all jobs for session have been run, not in the end like this
        return this.deleteTempSessions(Array.from(tempSessionsToDelete));
      }),

      tap(() => {
        logger.info("test set " + testSet + " took " + humanizeDuration(Date.now() - this.startTime.getTime()));
        this.restClient = null;
      }),
      map(() => this.stats)
    );
  }

  phenodataTypeCheck(dataset: Dataset) {
    return dataset.name.endsWith(".tsv") || dataset.name.endsWith(".TSV");
  }

  getSessionFiles(sessionPath) {
    const sessionFiles: string[] = [];
    if (fs.existsSync(sessionPath)) {
      if (fs.lstatSync(sessionPath).isDirectory()) {
        fs.readdirSync(sessionPath).forEach((file, index) => {
          const filePath = sessionPath + "/" + file;
          if (!fs.lstatSync(filePath).isDirectory()) {
            sessionFiles.push(filePath);
          }
        });
      } else {
        // one file
        sessionFiles.push(sessionPath);
      }
    } else {
      throw new Error("path " + sessionPath + " does not exist");
    }
    return sessionFiles;
  }

  deleteOldSessions(nameStart1, nameStart2) {
    logger.info("delete sessions starting with " + nameStart1 + " or " + nameStart2);
    return this.restClient.getSessions().pipe(
      map((sessions: Session[]) => {
        return sessions.filter(s => {
          return s.name.startsWith(nameStart1) || s.name.startsWith(nameStart2);
        });
      }),
      mergeMap((sessions: Session[]) => from(sessions)),
      mergeMap((session: Session) => {
        logger.info("delete session " + session.name);
        return this.restClient.deleteSession(session.sessionId);
      }),
      toArray()
    );
  }

  private deleteTempSessions(tempSessionIds: string[]) {
    return from(tempSessionIds).pipe(
      mergeMap(sessionId => {
        return this.restClient.deleteSession(sessionId);
      }),
      toArray()
    );
  }

  uploadSession(sessionFile: string, quiet: boolean): Observable<any> {
    const name =
      this.uploadSessionPrefix + path.basename(sessionFile).replace(".zip", "");

    return of(null).pipe(
      tap(() => logger.info("upload " + sessionFile)),
      mergeMap(() =>
        ChipsterUtils.sessionUpload(this.restClient, sessionFile, name, !quiet)
      ),
      mergeMap(id => this.restClient.getSession(id))
    );
  }

  getSessionJobPlans(
    originalSession: Session,
    quiet: boolean,
    allTools: Module[]
  ): Observable<JobPlan[]> {
    let jobSet;
    const datasetIdSet = new Set<string>();
    let replaySessionId: string;
    let filteredJobs: Job[];

    const toolsMap = new Map<string, Tool>();

    allTools.forEach(module => {
      module.categories.forEach(category => {
        category.tools.forEach(tool => {
          toolsMap.set(tool.name.id, tool);
        })
      })
    })

    const originalSessionId = originalSession.sessionId;

    const replaySessionName =
      this.replaySessionPrefix + path.basename(originalSession.name);

    return of(null).pipe(
      mergeMap(() => this.restClient.getDatasets(originalSessionId)),
      map((datasets: Dataset[]) => {
        // collect the list of datasets' sourceJobs
        jobSet = new Set(
          datasets.map(d => d.sourceJob).filter(id => id != null)
        );
        datasets.forEach(d => datasetIdSet.add(d.datasetId));
      }),
      mergeMap(() => this.restClient.getJobs(originalSessionId)),
      map((jobs: Job[]) => {
        return jobs
          // run only jobs whose output files exist
          // and don't care about failed or orphan jobs
          .filter(j => {
            // console.log("job", j.toolId, "results found", jobSet.has(j.jobId));
            return jobSet.has(j.jobId);
          })

          .filter(j => {
            // check inputs from the current tool, because the database doesn't store
            // inputs of deleted datasets
            if (toolsMap.has(j.toolId)) {
              const mandatoryInputs = toolsMap.get(j.toolId).inputs
              .filter(input => !input.optional);
              if (mandatoryInputs.length === 0) {
                // tool doesn't have any mandatory inputs, run it
                console.log("tool", j.toolId, "has no mandatory inputs");
                return true;
              }
            } else {
              /* Tool not found. Let it run to show an error in the results to get
              the session updated or removed. */
              console.log("cannot check inputs of tool " + j.toolId + " because it doesn't exist");
              return true;
            }

            /* Tool has mandatory inputs. Run only jobs that still have at least one
            input file in the session */
            for (const i of j.inputs) {
              if (datasetIdSet.has((i as any).datasetId)) {
                // console.log("job", j.toolId, "input found");
                return true;
              }
            }
            console.log("job", j.toolId, "input not found");
          })
          // a dummy job of the old Java client
          .filter(j => !ReplaySession.ignoreJobIds.includes(j.toolId));
      }),
      mergeMap((jobs: Job[]) => {

        logger.info(
          "session " +
            originalSession.name +
            " has " +
            jobs.length +
            " jobs"
        );

        if (jobs.length === 0) {
          // don't create session if there was nothing to run
          return of([]);
        } else {
          return of(null).pipe(
            mergeMap(() => {
              if (!quiet) {
                logger.info("create a new session");
              }
              return ChipsterUtils.sessionCreate(this.restClient, replaySessionName)
            }),
            map((id: string) => {
              replaySessionId = id;
              logger.info(
                "created temp session",
                replaySessionName,
                replaySessionId,
                "original was",
                originalSession.name,
                originalSessionId
              );

              const jobPlans = jobs
              .map(j => {
                return {
                  originalSessionId: originalSessionId,
                  replaySessionId: replaySessionId,
                  job: j,
                  originalSession: originalSession
                };
              });

              return jobPlans;
            }),
          );
        }
      })
    );
  }

  replayJob(
    plan: JobPlan,
    quiet: boolean,
    testSet: string,
    jobtimeout: number
  ): Observable<ReplayResult> {
    const job = plan.job;
    const originalSessionId = plan.originalSessionId;
    const replaySessionId = plan.replaySessionId;

    let originalDataset;
    let wsClient;
    const inputMap = new Map<string, Dataset>();
    const parameterMap = new Map<string, any>();
    const datasetsMap = new Map<string, Dataset>();
    const jobsMap = new Map<string, Job>();
    let tool: Tool;
    let metadataFiles: MetadataFile[] = [];
    let replayJobId;

    const timeout$ = new Subject();
    let timeoutSubscription;

    // why the type isn't recognized after adding the finzalize()?
    return of(null).pipe(
      mergeMap(() => this.restClient.getDatasets(job.sessionId)),
      tap((datasets: Dataset[]) =>
        datasets.forEach(d => datasetsMap.set(d.datasetId, d))
      ),
      mergeMap(() => this.restClient.getJobs(job.sessionId)),
      tap((jobs: Job[]) => jobs.forEach(j => jobsMap.set(j.jobId, j))),
      mergeMap(() => {
        const fileCopies = job.inputs.map(input => {
          return this.copyDatasetShallow(
            originalSessionId,
            replaySessionId,
            input.datasetId,
            job.jobId + input.datasetId,
            quiet
          ).pipe(
            mergeMap(datasetId =>
              this.restClient.getDataset(replaySessionId, datasetId)
            ),
            tap((dataset: Dataset) => inputMap.set(input.inputId, dataset)),
            tap((dataset: Dataset) => {
              if (!quiet) {
                logger.info(
                  "dataset " +
                    dataset.name +
                    " copied for input " +
                    input.inputId
                );
              }
            })
          );
        });
        return concat(...fileCopies).pipe(toArray());
      }),
      tap(() => {
        job.parameters.forEach(p => {
          parameterMap.set(p.parameterId, p.value);
          if (!quiet) {
            logger.info("set parameter " + p.parameterId + " " + p.value);
          }
        });
      }),
      mergeMap(() =>
        this.restClient.getTool(job.toolId).pipe(
          catchError(err => {
            if (err.statusCode === 404) {
              return throwError(
                new VError({
                  name: this.toolNotFoundError,
                  info: {
                    toolId: job.toolId
                  },
                  cause: err
                })
              );
            }
            return throwError(err);
          })
        )
      ),
      tap((t: Tool) => (tool = t)),
      tap(() => {
        metadataFiles = this.bindPhenodata(
          job,
          tool,
          datasetsMap,
          jobsMap,
          quiet
        );
      }),
      tap(() => {
        if (!quiet) {
          logger.info("connect websocket");
        }
        wsClient = new WsClient(this.restClient);
        wsClient.connect(replaySessionId, quiet);
      }),
      mergeMap(() =>
        ChipsterUtils.jobRunWithMetadata(
          this.restClient,
          replaySessionId,
          tool,
          parameterMap,
          inputMap,
          metadataFiles
        )
      ),
      mergeMap(jobId => {
        replayJobId = jobId;

        timeoutSubscription = timer(jobtimeout * 1000)
          .pipe(
            tap(() =>
              logger.info(
                testSet +
                  " " +
                  plan.originalSession.name +
                  " " +
                  plan.job.toolId +
                  " timed out after " +
                  jobtimeout +
                  " seconds"
              )
            ),
            mergeMap(() =>
              this.restClient.cancelJob(plan.replaySessionId, jobId)
            ),
            catchError(err => {
              logger.info(
                testSet +
                  " " +
                  plan.originalSession.name +
                  " " +
                  plan.job.toolId +
                  " cancel failed: " +
                  err
              );
              return of(null);
            })
          )
          .subscribe(() => {
            // make sure the replay tests continue even if the cancelling fails
            try {
              timeout$.next();
            } catch (err) {
              if (err instanceof ObjectUnsubscribedError) {
                // cancelling completed the WebSocket stream already
              } else {
                logger.error(
                  new VError(err, "failed to cancel job " + plan.job.toolId)
                );
              }
            }
          });
        wsClient
          .getJobScreenOutput$(jobId)
          .pipe(takeUntil(timeout$))
          .subscribe(
            output => {
              if (!quiet) {
                // process.stdout.write(output);
                logger.info(output);
              }
            },
            err => {
              logger.error(
                new VError(
                  err,
                  "failed to get the screen output, test set " + testSet
                )
              );
            }
          );
        return wsClient
          .getJobState$(jobId)
          .pipe(takeUntil(timeout$))
          .pipe(
            tap((job: Job) => {
              if (!quiet) {
                logger.info(
                  "*",
                  job.state,
                  "(" + (job.stateDetail || "") + ")"
                );
              }
            }),
            // the first null sets no filter, the second null is emitted if there wasn't any job state changes
            // before the the observable completed (e.g. when timeout === 0). Otherwise last() would terminate
            // with EmptyError
            last(null, null)
          );
      }),
      mergeMap(() =>
        this.compareOutputs(
          job.jobId,
          replayJobId,
          originalSessionId,
          replaySessionId,
          plan
        )
      ),
      catchError(err => {
        if (VError.hasCauseWithName(err, this.toolNotFoundError)) {
          logger.warn("tool not found: " + err.message);
        } else if (VError.hasCauseWithName(err, missingInputError)) {
          logger.warn("missing input error: " + err);
        } else {
          // unexpected technical problems
          logger.error(
            "unexpected error " + testSet + " " + job.toolId + ": " + err
          );
          logger.error(
            new VError(err, "unexpected error " + testSet + " " + job.toolId)
          );
        }
        logger.info("convert to replay result");
        return of(this.errorToReplayResult(err, job, plan));
      }),
      // why exceptions (like missing tool) interrupt the parallel run if this is before catchError()?
      finalize(() => {
        if (wsClient != null) {
          wsClient.disconnect();
        }
        if (timeoutSubscription != null) {
          timeoutSubscription.unsubscribe();
        } else {
          logger.warn(
            "timeoutSubscription was null, can't unsubscribe",
            plan,
            testSet
          );
        }
      })
    ) as any;
  }

  bindPhenodata(
    job: Job,
    tool: Tool,
    datasetsMap: Map<string, Dataset>,
    jobsMap: Map<string, Job>,
    quiet: boolean
  ): MetadataFile[] {
    // this is almost like ToolService.bindPhenodata() in the client, but can get the phenodata also from the old job

    // if no phenodata inputs, return empty array
    const phenodataInputs = tool.inputs.filter(input => input.meta);
    if (phenodataInputs.length === 0) {
      return [];
    }

    if (job.metadataFiles != null && job.metadataFiles.length > 0) {
      if (!quiet) {
        logger.info("using phenodata from the old job");
      }
      return job.metadataFiles;
    } else {
      if (!quiet) {
        logger.info(
          "the old job doesn't have phenodata (session from Java client), try to find it from the inputs"
        );
      }
      // for now, if tool has multiple phenodata inputs, don't try to bind anything
      // i.e. return array with phenodata inputs but no bound datasets
      if (phenodataInputs.length > 1) {
        logger.error(
          "multiple phenodata inputs are not supported, toolId: " + tool.name.id
        );
        return [];
      }

      // try to bind the first (and only, see above) phenodata input
      const firstPhenodataInput = phenodataInputs[0];

      // get the datasetIds of all potential phenodatas (to remove duplicates soon)
      const phenodataDatasetIds = job.inputs
        .map(input => {
          const dataset = datasetsMap.get(input.datasetId);
          const phenodataDataset = PhenodataUtils.getPhenodataDataset(
            dataset,
            jobsMap,
            datasetsMap,
            d => this.phenodataTypeCheck(d)
          );
          return phenodataDataset;
        })
        .filter(dataset => dataset != null)
        .map(dataset => dataset.datasetId);
      // remove duplicates (in case multiple inputs point to the same phenodata)
      const uniquePhenodataDatasetIds = Array.from(
        new Set(phenodataDatasetIds)
      );

      if (uniquePhenodataDatasetIds.length === 0) {
        logger.error("can't bind phenodata, inputs have no phenodata");
      } else if (uniquePhenodataDatasetIds.length > 1) {
        logger.error(
          "can't bind phenodata, inputs have multiple phenodatas (rerun the job in the Chipster app to bind the correct phenodata)"
        );
      } else {
        const phenodataDataset = datasetsMap.get(uniquePhenodataDatasetIds[0]);
        const phenodata = PhenodataUtils.getOwnPhenodata(phenodataDataset);
        if (!quiet) {
          logger.info(
            "found phenodata for job input " +
              firstPhenodataInput.name.id +
              " from dataset " +
              phenodataDataset.name
          );
        }
        return [{ name: firstPhenodataInput.name.id, content: phenodata }];
      }
    }
  }

  errorToReplayResult(err, job: Job, plan: JobPlan): ReplayResult {
    const j = _.clone(job);
    j.state = "REPLAY_ERROR";
    j.stateDetail = "(see replay logs)";
    j.screenOutput = "";
    return {
      job: j,
      messages: [],
      errors: [err],
      sessionName: plan.originalSession.name
    };
  }

  compareOutputs(
    jobId1: string,
    jobId2: string,
    sessionId1: string,
    sessionId2: string,
    plan: JobPlan
  ): Observable<ReplayResult> {
    let outputs1;
    let outputs2;

    return forkJoin(
      this.restClient.getDatasets(sessionId1),
      this.restClient.getDatasets(sessionId2)
    ).pipe(
      map((res: any[]) => {
        outputs1 = res[0]
          .filter(d => d.sourceJob === jobId1)
          .sort((a, b) => a.name.localeCompare(b.name));
        outputs2 = res[1]
          .filter(d => d.sourceJob === jobId2)
          .sort((a, b) => a.name.localeCompare(b.name));
      }),
      mergeMap(() => this.restClient.getJob(sessionId2, jobId2)),
      map((job2: Job) => {
        const errors = [];
        const messages = [];

        if (!WsClient.successStates.includes(job2.state)) {
          errors.push("unsuccessful job state");
        } else {
          if (outputs1.length === outputs2.length) {
            messages.push(
              "correct number of outputs (" + outputs2.length + ")"
            );
          } else {
            errors.push(
              "different number of outputs: expected " +
                outputs1.length +
                " but found " +
                outputs2.length
            );
          }

          const names1 = outputs1.map(d => d.name);
          const names2 = outputs2.map(d => d.name);
          if (_.isEqual(names1, names2)) {
            messages.push("correct dataset names");
          } else {
            errors.push(
              "different dataset names: expected " +
                names1 +
                " but found " +
                names2
            );
          }

          for (let i = 0; i < Math.min(outputs1.length, outputs2.length); i++) {
            const d1 = outputs1[i];
            const d2 = outputs2[i];
            const sizeDiff = ((d2.size - d1.size) / (1.0 * d1.size)) * 100;
            if (d1.size === d2.size) {
              messages.push(
                'the size of the dataset "' +
                  d1.name +
                  '" is correct (' +
                  ChipsterUtils.toHumanReadable(d2.size) +
                  ")"
              );
            } else if (sizeDiff >= -90 && sizeDiff <= 900) {
              // percent
              messages.push(
                'the size of the dataset "' +
                  d1.name +
                  '" is close enough (' +
                  Math.round(sizeDiff) +
                  "%)"
              );
            } else {
              errors.push(
                'the size of the dataset "' +
                  d1.name +
                  '" differs too much (' +
                  Math.round(sizeDiff) +
                  "%)"
              );
            }
          }
        }
        return {
          job: job2,
          messages: messages,
          errors: errors,
          sessionName: plan.originalSession.name
        };
      })
    );
  }

  copyDatasetShallow(
    originalSessionId: string,
    replaySessionId: string,
    datasetId: string,
    tempFileName: string,
    quiet: boolean
  ) {
    return this.restClient.getDataset(originalSessionId, datasetId).pipe(
      mergeMap((dataset: Dataset) => {
        // create a new dataset pointing to the same fileId
        dataset.datasetId = null;
        dataset.sessionId = null;
        return this.restClient.postDataset(replaySessionId, dataset);
      })
    );
  }

  copyDatasetDeep(
    originalSessionId: string,
    replaySessionId: string,
    datasetId: string,
    tempFileName: string,
    quiet: boolean
  ) {
    const localFileName = this.tempPath + "/" + tempFileName;
    let dataset;
    let copyDatasetId;

    return this.restClient.getDataset(originalSessionId, datasetId).pipe(
      tap(d => (dataset = d)),
      tap(() => fs.mkdirSync(this.tempPath)),
      tap(() => {
        if (!quiet) {
          logger.info(
            "copy dataset " +
              dataset.name +
              " " +
              ChipsterUtils.toHumanReadable(dataset.size)
          );
        }
      }),
      mergeMap(() =>
        this.restClient.downloadFile(
          originalSessionId,
          datasetId,
          localFileName
        )
      ),
      mergeMap(() =>
        ChipsterUtils.datasetUpload(
          this.restClient,
          replaySessionId,
          localFileName,
          dataset.name
        )
      ),
      tap(id => (copyDatasetId = id)),
      mergeMap(copyDatasetId =>
        this.restClient.getDataset(replaySessionId, copyDatasetId)
      ),
      mergeMap((copyDataset: Dataset) => {
        copyDataset.metadataFiles = dataset.metadataFiles;
        return this.restClient.putDataset(replaySessionId, copyDataset);
      }),
      tap(() => fs.unlinkSync(localFileName)),
      map(() => copyDatasetId)
    );
  }

  removeAllFiles(path: string) {
    if (fs.existsSync(path)) {
      fs.readdirSync(path).forEach((file, index) => {
        const filePath = path + "/" + file;
        if (
          !fs.lstatSync(filePath).isDirectory() &&
          (file.endsWith(".txt") || file.endsWith(".html"))
        ) {
          fs.unlinkSync(filePath);
        }
      });
    }
  }

  writeResults(
    results,
    importErrors: ImportError[],
    isCompleted: boolean,
    jobPlanCount: number,
    testSet: string,
    allTools: Array<Module>
  ): Observable<any> {
    this.removeAllFiles(this.resultsPath);

    const allToolIds = new Set<string>();

    allTools.forEach(module => {
      module.categories.forEach(category => {
        category.tools.forEach(tool => {
          allToolIds.add(tool.name.id);
        });
      });
    });

    this.writeResultsSync(
      results,
      importErrors,
      isCompleted,
      allToolIds,
      jobPlanCount,
      testSet
    );
    return of(null);
  }

  writeResultsSync(
    results,
    importErrors: ImportError[],
    isCompleted: boolean,
    allToolIds: Set<string>,
    jobPlanCount: number,
    testSet: string
  ) {
    const booleanResults = results.map(
      r => r.job.state === "COMPLETED" && r.errors.length === 0
    );

    const totalCount = jobPlanCount;
    const finishedCount = results.length;
    const okCount = booleanResults.filter(b => b).length;
    const failCount = booleanResults.filter(b => !b).length;
    const totalTime = this.dateDiff(this.startTime, new Date());

    const uniqTestToolsCount = _.uniq(results.map(r => r.job.toolId)).length;

    const coverageCounts = new Map<string, number>();
    const coverageSessions = new Map<string, Set<string>>();

    allToolIds.forEach(toolId => {
      coverageCounts.set(toolId, 0);
      coverageSessions.set(toolId, new Set<string>());
    });

    results.forEach(r => {
      const toolId = r.job.toolId;

      if (coverageCounts.has(toolId)) {
        coverageCounts.set(toolId, coverageCounts.get(toolId) + 1);
        coverageSessions.get(toolId).add(r.sessionName);
      }
    });

    const sortedToolIds = Array.from(allToolIds).sort(
      (a, b) => coverageCounts.get(b) - coverageCounts.get(a)
    );

    const stream = fs.createWriteStream(this.resultsPath + "/index.html");
    stream.once("open", fd => {
      stream.write(`
<html>
<head>
<style>
th {
    border-bottom: 1px solid black;
    border-collapse: collapse;
}
th, td {
    padding: 5px;
}
th {
    text-align: left;
}
.error-cell {
    border-left-color: red;
    border-left-width: 4px;
    border-left-style: solid;
}
</style>
</head>
<body>
            `);

      const runningState = isCompleted ? "completed" : "running";

      if (failCount === 0 && importErrors.length === 0) {
        if (isCompleted) {
          if (okCount > 0) {
            stream.write(
              "<h2>Tool tests " +
                testSet +
                " " +
                runningState +
                ' - <span style="color: green">everything ok!</span></h2>'
            );
          } else {
            stream.write(
              "<h2>Tool tests  " +
                testSet +
                ' - <span style="color: orange">not found!</span></h2>'
            );
          }
        } else {
          stream.write(
            "<h2>Tool tests  " + testSet + " " + runningState + "</h2>"
          );
        }
      } else {
        stream.write(
          "<h2>Tool tests  " +
            testSet +
            " " +
            runningState +
            ' - <span style="color: red">' +
            failCount +
            " tool(s) failed, " +
            importErrors.length +
            " session(s) with errors</span></h2>"
        );
      }

      stream.write("<h3>Summary</h3>");
      stream.write("<table>");
      stream.write("<tr>");
      stream.write("<td>Results summary</td>");
      stream.write(
        "<td>" +
          okCount +
          " ok, " +
          failCount +
          " failed, " +
          finishedCount +
          " finished, " +
          totalCount +
          " total</td>"
      );
      stream.write("</tr>");

      stream.write("<tr>");
      stream.write("<td>Tool coverage</td>");
      stream.write(
        "<td>" + uniqTestToolsCount + " / " + allToolIds.size + "</td>"
      );
      stream.write("</tr>");

      stream.write("<tr>");
      stream.write("<td>Total time</td>");
      stream.write(
        "<td>" + this.millisecondsToHumanReadable(totalTime) + "</td>"
      );
      stream.write("</tr>");

      stream.write("<tr>");
      stream.write("<td>Start time</td>");
      stream.write("<td>" + this.startTime + "</td>");
      stream.write("</tr>");
      stream.write("</table>");

      if (importErrors.length > 0) {
        stream.write(`
                <h3>Session with errors</h3>
                <table>
                <tr>
                <th>Session</th>
                <th>Error</th>
                <th>Stacktrace</th>
                </tr>
                `);

        importErrors.forEach(importError => {
          stream.write("<tr><td>" + importError.file + "</td>");
          stream.write("<td>" + importError.error.message + "</td>");
          const errFile =
            Math.random()
              .toString()
              .replace(".", "") + ".txt";
          stream.write(
            '<td><a href = "' + errFile + '" > Stacktrace </a></td>'
          );
          // write the sreen output to a separate file
          const s2 = fs.createWriteStream(this.resultsPath + "/" + errFile);
          s2.once("open", function(fd) {
            s2.write(importError.error.message + importError.error.stack);
            s2.end();
          });
          stream.write("</tr>");
        });
        stream.write("</table>");
      }

      stream.write(`
<h3>Tool test results</h3>
<table>
<tr>
<th>Session</th>
<th>Tool id</th>
<th>Job state</th>
<th>Successful tests</th>
<th>Test errors</th>
<th>Job state detail</th>
<th>Duration</th>
</tr>
            `);
      results.forEach(r => {
        stream.write("<tr>\n");
        const errorClass = ' class="error-cell"';
        const stateOk = r.job.state === "COMPLETED";
        const errorsOk = r.errors.length === 0;
        const stateStyle = stateOk ? "" : errorClass;
        const errorsStyle = errorsOk ? "" : errorClass;
        const duration = this.millisecondsToHumanReadable(
          this.dateDiff(r.job.startTime, r.job.endTime)
        );

        stream.write("<td>" + r.sessionName + "</td>\n");
        stream.write("<td>" + r.job.toolId + "</td>\n");
        stream.write("<td" + stateStyle + ">" + r.job.state + "</td>\n");
        stream.write("<td>" + r.messages.join("<br>") + "</td>\n");
        stream.write(
          "<td" + errorsStyle + ">" + r.errors.join("<br>") + "</td>\n"
        );
        stream.write("<td>");
        stream.write(r.job.stateDetail + "<br>");
        if (r.job.screenOutput == null) {
          stream.write("Screen output is null");
        } else {
          stream.write(
            '<a href = "' +
              r.job.jobId +
              '.txt" > Screen output (' +
              ChipsterUtils.toHumanReadable(r.job.screenOutput.length) +
              " B) </a>"
          );
          // write the sreen output to a separate file
          const s2 = fs.createWriteStream(
            this.resultsPath + "/" + r.job.jobId + ".txt"
          );
          s2.once("open", function(fd) {
            s2.write(r.job.screenOutput);
            s2.end();
          });
        }
        stream.write("</td >\n");
        stream.write("<td>" + duration + "</td>\n");
        stream.write("</tr>\n");
      });
      stream.write("</table>\n");

      stream.write(`
<h3>Coverage</h3>
<table>
<tr>
<th>Tool id</th>
<th>Count</th>
<th>Sessions</th>
</tr>
            `);
      sortedToolIds.forEach(toolId => {
        stream.write("<tr>\n");
        stream.write("<td>" + toolId + "</td>\n");
        stream.write("<td>" + coverageCounts.get(toolId) + "</td>\n");
        stream.write(
          "<td>" +
            Array.from(coverageSessions.get(toolId)).join(", ") +
            "</td>\n"
        );
      });
      stream.write("</table></body></html>\n");

      stream.end();
    });

    // create, update or delete the flag file based on the result
    if (isCompleted) {
      const flagPath = this.resultsPath + "/" + this.flagFile;
      if (failCount === 0 && okCount > 0) {
        // create the file or update its mtime
        fs.writeFileSync(flagPath, "test-ok");
      } else {
        if (fs.existsSync(flagPath)) {
          logger.info("deleting flag file");
          fs.unlinkSync(flagPath);
        } else {
          logger.info("no previous flag file to delete");
        }
      }

      this.stats.set("okCount", okCount);
      this.stats.set("failCount", failCount);
      this.stats.set("totalCount", totalCount);
      this.stats.set("uniqueTestToolsCount", uniqTestToolsCount);
      this.stats.set("allToolsCount", allToolIds.size);
      this.stats.set("totalTime", totalTime);
    }
  }

  dateDiff(start, end) {
    return Date.parse(end) - Date.parse(start);
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

  private filterArrayToStringfilter(filters: string[]): string {
    const result = filters.reduce((aggregate, filter, index, filters) => {
      const s = filter.replace("/", "_");
      return aggregate + (s.endsWith("_") ? s : s + "_");
    }, "");
    return result.endsWith("_") ? result.slice(0, -1) : result;
  }
}

export class ReplayResult {
  job: Job;
  messages: string[];
  errors: string[];
  sessionName: string;
}

export class ImportError {
  file: string;
  error: Error;
}

export class UploadResult {
  originalSessionId: string;
  replaySessionId: string;
  originalSession: Session;
}

export class JobPlan {
  originalSessionId: string;
  replaySessionId: string;
  job: Job;
  originalSession: Session;
}

if (require.main === module) {
  new ReplaySession().parseCommand();
}