import { Dataset, Job, WsEvent } from "chipster-js-common";
import { Logger, RestClient } from "chipster-nodejs-core";
import { ReplaySubject, Subject } from "rxjs";
import {
  concat,
  distinctUntilChanged,
  filter,
  map,
  mergeMap,
  multicast,
  pairwise,
  startWith,
  take,
  takeWhile
} from "rxjs/operators";
import { VError } from "verror";
const WebSocket = require("ws");

const path = require("path");
const read = require("read");
const ArgumentParser = require("argparse").ArgumentParser;
const logger = Logger.getLogger(__filename);

export default class WsClient {
  sessionId: string;
  wsEvents$ = new Subject<WsEvent>();
  ws;

  static readonly failedStates = [
    "FAILED",
    "FAILED_USER_ERROR",
    "ERROR",
    "CANCELLED",
    "TIMEOUT",
    "EXPIRED_WAITING"
  ];

  static readonly successStates = ["COMPLETED"];

  static readonly finalStates = WsClient.failedStates.concat(
    WsClient.successStates
  );

  constructor(private restClient: RestClient) {}

  connect(sessionId: string, quiet = false) {
    this.sessionId = sessionId;
    return this.restClient.getSessionDbEventsUri().subscribe(url => {
      url = url + "/events/" + sessionId + "?token=" + this.restClient.token;
      let previousScreenOutput = "";

      this.ws = new WebSocket(url);

      if (!quiet) {
        this.ws.on("open", () => {
          logger.info("websocket connected");
        });
      }

      this.ws.on("message", data => {
        const event = JSON.parse(data);
        this.wsEvents$.next(event);
      });

      if (!quiet) {
        this.ws.on("close", (code, reason) => {
          if (code === 1001) {
            // idle timeout
            logger.info("websocket " + reason + ", reconnecting...");
            this.connect(sessionId, quiet);
          } else {
            logger.info("websocket closed " + code + reason);
            +this.wsEvents$.error("websocket closed: " + code + " " + reason);
          }
        });

        this.ws.on("error", error => {
          logger.error(new VError(error, "websocket error"));
          this.wsEvents$.error("websocket error: " + error);
        });
      }
    });
  }

  /**
   * Get job's events
   *
   * Both successful and failed final states complete the stream normally. To recognize
   * job failures, a subscriber can compare the job state agains the WsClient.failedStates array.
   * The stream error is reserved for unexpected exceptions.
   *
   * @param jobId
   */
  getJob$(jobId: string) {
    if (jobId == null) {
      throw new Error("jobId is " + jobId);
    }

    return this.wsEvents$.pipe(
      filter(
        (e: WsEvent) => e.resourceType === "JOB" && e.resourceId === jobId
      ),
      mergeMap(e => this.restClient.getJob(this.sessionId, jobId)),
      /* 
      All this multicasting just to get the last event also in takeWhile().
      takeWhile(..., true) would do the same since rxjs 6.4, but rx-http-request seems
      to get stuck with it when following the job output.
      */
      multicast(
        () => new ReplaySubject(1),
        subject => {
          return subject.pipe(
            takeWhile(
              (job: Job) => WsClient.finalStates.indexOf(job.state) === -1
            ),
            concat(subject.pipe(take(1)))
          );
        }
      )
    );
  }

  getJobState$(jobId: string) {
    return this.getJob$(jobId).pipe(
      distinctUntilChanged((a: Job, b: Job) => {
        return a.state === b.state && a.stateDetail === b.stateDetail;
      })
    );
  }

  getJobScreenOutput$(jobId: string) {
    let warned = false;
    return this.getJob$(jobId).pipe(
      map((job: Job) => job.screenOutput),
      filter(output => output != null),
      startWith(""),
      pairwise(),
      map(outputPair => {
        // It's hard to get perfect copies of the screen output
        // when we may miss some object versions, but it's good enough
        // for human eyes.
        if (!outputPair[1].startsWith(outputPair[0])) {
          let output = null;
          // remove the longest start of the new output that matches with the end
          // of the previous output
          for (let i = outputPair[1].length; i > 0; i--) {
            if (outputPair[0].endsWith(outputPair[1].slice(0, i))) {
              output = outputPair[1].slice(i);
              break;
            }
          }
          if (output == null) {
            output = outputPair[1];
          }

          if (!warned) {
            output = "\n--- output mangled ---\n" + output;
            warned = true;
          }
          return output;
        }
        return outputPair[1].slice(outputPair[0].length);
      })
    );
  }

  getJobOutputDatasets$(jobId: string) {
    return this.wsEvents$.pipe(
      filter(
        (event: WsEvent) =>
          event.resourceType === "DATASET" && event.type === "CREATE"
      ),
      // we have to get all created datasets to see if
      // it was created by this job
      mergeMap((event: WsEvent) =>
        this.restClient.getDataset(this.sessionId, event.resourceId)
      ),
      filter((dataset: Dataset) => dataset.sourceJob === jobId)
    );
  }

  disconnect() {
    if (this.ws != null) {
      this.ws.close();
    }
  }
}
