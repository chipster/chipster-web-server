import CliEnvironment from "./cli-environment";
import { Subject } from "rxjs";
import { filter, mergeMap, takeWhile, tap, distinctUntilChanged, map, startWith, pairwise } from 'rxjs/operators';
//const RestClient = require('rest-client')

import * as _ from 'lodash';
import { RestClient, Logger } from "rest-client";
const WebSocket = require('ws');

const path = require('path');
const read = require('read');
const ArgumentParser = require('argparse').ArgumentParser;
//const logger = Logger.getLogger(__filename);

export default class WsClient {

    sessionId: string;
    wsEvents$ = new Subject<any>();
    ws;

    constructor(private restClient: RestClient) {        
    }

    connect(sessionId: string) {
        this.sessionId = sessionId;
        return this.restClient.getSessionDbEventsUri()
        .subscribe(url => {        
          url = url + '/events/' + sessionId + '?token=' + this.restClient.token;
          let previousScreenOutput = '';
  
          this.ws = new WebSocket(url);
  
          this.ws.on('open', () =>  {
            console.log('websocket connected');
          });
  
          this.ws.on('message', data => {
              const event = JSON.parse(data);
              this.wsEvents$.next(event);              
          });
  
          this.ws.on('close', (code, reason) => {
            console.log('websocket closed', code, reason);
          });
  
          this.ws.on('error', error => {
            console.log('websocket error', error);
          });
        })
    }

    getJob$(jobId: string) {

        if (jobId == null) {
            throw new Error('jobId is ' + jobId);
        }

        const failedStates = [
            'FAILED', 
            'FAILED_USER_ERROR',
            'ERROR',
            'CANCELLED',
            'TIMEOUT',
            'EXPIRED_WAITING']          

        const successStates = [
            'COMPLETED']          

        return this.wsEvents$.pipe(
            filter((e: any) => e.resourceType === 'JOB' && e.resourceId === jobId),
            mergeMap(e => this.restClient.getJob(this.sessionId, jobId)),
            tap((job: any) => {
                if (failedStates.indexOf(job.state) !== -1) {
                    throw Error('job ' + job.state + ': ' + job.stateDetail + "\nscreen output:\n" + job.screenOutput);
                }
            }),
            takeWhile((job: any) => successStates.indexOf(job.state) === -1));
    }

    getJobState$(jobId: string) {
        return this.getJob$(jobId).pipe(
            distinctUntilChanged((a: any, b: any) => {
                return a.state === b.state && a.stateDetail === b.stateDetail;
            }));
    }

    getJobScreenOutput$(jobId: string) {
        let warned = false;
        return this.getJob$(jobId).pipe(
            map((job: any) => job.screenOutput),
            filter(output => output != null),
            startWith(''),
            pairwise(),
            map(outputPair => {
                // It's hard to get perfect copies of the screen output
                // when we may miss some object versions, but it's good enough
                // for human eyes.
                if (!outputPair[1].startsWith(outputPair[0])) {
                    if (!warned) {                                
                        console.error('\n--- output mangled ---\n');
                        warned = true;
                    }
                    // remove the longest start of the new output that matches with the end 
                    // of the previous output
                    for (let i = outputPair[1].length; i > 0; i--) {
                        if (outputPair[0].endsWith(outputPair[1].slice(0, i))) {
                            return outputPair[1].slice(i);
                        } 
                    }
                    return outputPair[1];
                }
                return outputPair[1].slice(outputPair[0].length);
            }));
    }

    getJobOutputDatasets$(jobId: string) {
        return this.wsEvents$.pipe(
            filter((event: any) => event.resourceType === 'DATASET' && event.type === 'CREATE'),
            // we have to get all created datasets to see if 
            // it was created by this job
            mergeMap((event: any) => this.restClient.getDataset(this.sessionId, event.resourceId)),
            filter((dataset: any) => dataset.sourceJob === jobId));
    }

    disconnect() {
        this.ws.close();
    }
}