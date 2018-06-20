import {Logger} from "../../type-service/src/logger";
import CliEnvironment from "./cli-environment";
import { Observable } from "rxjs";
import { Subject } from "rxjs";
import 'rxjs/add/observable/of';
import 'rxjs/add/observable/from';
import 'rxjs/add/observable/forkJoin';
import 'rxjs/add/observable/empty';
import 'rxjs/add/operator/do';
import 'rxjs/add/operator/map';
import 'rxjs/add/operator/mergeMap';
import 'rxjs/add/operator/toArray';
import 'rxjs/add/operator/filter';
import 'rxjs/add/operator/takeWhile';
import 'rxjs/add/operator/pairwise';
import 'rxjs/add/operator/distinctUntilChanged';
import 'rxjs/add/operator/startWith';
import 'rxjs/add/operator/finally';

import { RestClient } from "../../type-service/src/rest-client";
import * as _ from 'lodash';
const WebSocket = require('ws');

const path = require('path');
const read = require('read');
const ArgumentParser = require('argparse').ArgumentParser;
const logger = Logger.getLogger(__filename);

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

        return this.wsEvents$
            .filter(e => e.resourceType === 'JOB' && e.resourceId === jobId)
            .mergeMap(e => this.restClient.getJob(this.sessionId, jobId))
            .do(job => {
                if (failedStates.indexOf(job.state) !== -1) {
                    throw Error('job ' + job.state + ': ' + job.stateDetail);
                }
            })
            .takeWhile(job => successStates.indexOf(job.state) === -1);
    }

    getJobState$(jobId: string) {
        return this.getJob$(jobId)
            .distinctUntilChanged((a, b) => {
                return a.state === b.state && a.stateDetail === b.stateDetail;
            });
    }

    getJobScreenOutput$(jobId: string) {
        let warned = false;
        return this.getJob$(jobId)
            .map(job => job.screenOutput)
            .filter(output => output != null)
            .startWith('')
            .pairwise()
            .map(outputPair => {
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
            });
    }

    getJobOutputDatasets$(jobId: string) {
        return this.wsEvents$
            .filter(event => event.resourceType === 'DATASET' && event.type === 'CREATE')
            // we have to get all created datasets to see if 
            // it was created by this job
            .mergeMap(event => this.restClient.getDataset(this.sessionId, event.resourceId))
            .filter(dataset => dataset.sourceJob === jobId);
    }

    disconnect() {
        this.ws.close();
    }
}