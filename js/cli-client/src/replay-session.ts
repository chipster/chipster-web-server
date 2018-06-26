import { Observable, observable, Subject, forkJoin, of, concat } from "rxjs";
import { tap, mergeMap, toArray, take, map, finalize, catchError } from "rxjs/operators";
import { RestClient, Logger } from "rest-client";
import ChipsterUtils from "./chipster-utils";
import wsClient from "./ws-client";
import WsClient from "./ws-client";
import * as _ from 'lodash';

const ArgumentParser = require('argparse').ArgumentParser;
const fs = require('fs');
const logger = Logger.getLogger(__filename);

/**
 * Session replay test
 * 
 * Replay the jobs in a session to test that the tools still work.
 */
export default class ReplaySession {
    
    restClient: any;
    replaySessionId: {};
    originalSessionId: {};
    
    constructor() {
        this.parseCommand();
    }
    
    parseCommand() {
        
        let parser = new ArgumentParser({
            version: '0.0.1',
            addHelp:true,
            description: 'Chipster session replay test',
        });
        
        
        parser.addArgument(['URL'], { help: 'url of the API server'});
        parser.addArgument(['--username', '-u'], { help: 'username for the server'});
        parser.addArgument(['--password', '-p'], { help: 'password for the server' });
        
        parser.addArgument(['session'], { help: 'session file to replay' });
        
        let args = parser.parseArgs();
        
        this.replaySession(args);
    }
    
    replaySession(args) {

        let jobSet;
        
        console.log('login as', args.username);
        ChipsterUtils.login(args.URL, args.username, args.password).pipe(
            mergeMap((token: any) => ChipsterUtils.getRestClient(args.URL, token.tokenKey)),
            tap(restClient => this.restClient = restClient),
            tap(() => console.log('logged in as', args.username)),
            tap(() => console.log('upload the original sesssion file')),
            mergeMap(() => ChipsterUtils.sessionUpload(this.restClient, args.session, null, true)),
            tap(id => this.originalSessionId = id),
            tap(() => console.log('create a new session')),
            mergeMap(() => ChipsterUtils.sessionCreate(this.restClient, 'session-replay-test')),
            tap(id => this.replaySessionId = id),
            mergeMap(() => this.restClient.getDatasets(this.originalSessionId)),
            map((datasets: any[]) => {
                // collect the list of datasets' sourceJobs
                jobSet = new Set(datasets
                    .map(d => d.sourceJob)
                    .filter(id => id != null));
            }),
            mergeMap(() => this.restClient.getJobs(this.originalSessionId)),
            mergeMap((jobs: any[]) => {
                const replays = jobs
                    // run only jobs whose output files exist
                    // and don't care about failed or orphan jobs
                    .filter(j => jobSet.has(j.jobId))
                    // a dummy job of the old Java client
                    .filter(j => j.toolId !== 'operation-definition-id-import')
                    .map(j => this.replayJob(j));
                return concat(...replays).pipe(toArray());
            }),
            catchError(err => {
                // handle errors here to make sure the sessions are always deleted
                console.error('sesssion replay error', err);
                return of(null);
            }),
            mergeMap(() => this.restClient.deleteSession(this.originalSessionId)),
            mergeMap(() => this.restClient.deleteSession(this.replaySessionId)),
        ).subscribe(
            () => console.log('session replay done'),
            err => console.error('clean up failed', err),
            () => console.log('session replay completed'));
    }
    
    replayJob(job) {

        let originalDataset;
        let wsClient;
        const inputMap = new Map();
        const parameterMap = new Map();        
        const jobSubject = new Subject();
        let replayJobId;
        
        return of(null).pipe(
            tap(() => console.log('replay job ' + job.toolId)),
            mergeMap(() => {
                const fileCopies = job.inputs.map(input => {
                    return this.copyDataset(this.originalSessionId, this.replaySessionId, input.datasetId).pipe(
                        mergeMap(datasetId => this.restClient.getDataset(this.replaySessionId, datasetId)),
                        tap(dataset => inputMap.set(input.inputId, dataset)),
                        tap((dataset: any) => console.log('dataset ' + dataset.name + ' copied for input ' + input.inputId)),
                    );
                });
                return concat(...fileCopies).pipe(toArray());
            }),            
            tap(() => {
                console.log('connect websocket');
                wsClient = new WsClient(this.restClient);
                wsClient.connect(this.replaySessionId);
            }),
            mergeMap(() => this.restClient.getTool(job.toolId)),
            mergeMap(tool => ChipsterUtils.jobRun(this.restClient, this.replaySessionId, tool, parameterMap, inputMap)),
            mergeMap(jobId => {
                replayJobId = jobId;
                wsClient.getJobState$(jobId).subscribe(job => {
                    console.log('*', job.state, '(' + (job.stateDetail || '') + ')');
                }, err => {
                    console.error('websocket error', err);
                }, () => {
                    jobSubject.next();
                    jobSubject.complete();
                });
                wsClient.getJobScreenOutput$(jobId).subscribe(output => {
                    process.stdout.write(output);
                });
                return jobSubject;
            }),
            finalize(() => wsClient.disconnect()),
            mergeMap(() => this.compareOutputs(job.jobId, replayJobId, this.originalSessionId, this.replaySessionId)),
        );
    }

    compareOutputs(jobId1, jobId2, sessionId1, sessionId2) {
        return forkJoin(this.restClient.getDatasets(sessionId1), this.restClient.getDatasets(sessionId2)).pipe(
            tap((res: any[]) => {
                const outputs1 = res[0]
                    .filter(d => d.sourceJob === jobId1)
                    .sort((a, b) => a.name.localeCompare(b.name));
                const outputs2 = res[1]
                    .filter(d => d.sourceJob === jobId2)
                    .sort((a, b) => a.name.localeCompare(b.name));

                if (outputs1.length !== outputs2.length) {
                    throw Error('different number of outputs: expected ' +  outputs1.length + ' but found ' + outputs2.length);
                }
                console.log('correct number of outputs (' + outputs2.length + ')');

                const names1 = outputs1.map(d => d.name);
                const names2 = outputs2.map(d => d.name);
                if (!_.isEqual(names1, names2)) {
                    throw Error('different dataset names: expected ' + names1 + ' but found ' + names2);
                }
                console.log('correct dataset names');

                for (let i = 0; i < outputs1.length; i++) {
                    const d1 = outputs1[i];
                    const d2 = outputs2[i];
                    const sizeDiff = (d2.size - d1.size) / (1.0 * d1.size) * 100;
                    if (d1.size === d2.size) {
                        console.log('the size of the dataset "' + d1.name + '" is correct (' + ChipsterUtils.toHumanReadable(d2.size) + ')');
                    } else if (Math.abs(sizeDiff) < 30) { // percent
                        console.log('the size of the dataset "' + d1.name + '" is close enough (' + Math.round(sizeDiff) + '%)');
                    } else {
                        throw Error('the size of the dataset "' + d1.name + '" differs too much (' + Math.round(sizeDiff) + '%)');
                    }
                }
            })
        );            
    }
    
    copyDataset(fromSessionId, toSessionid, datasetId) {
        const localFileName = datasetId;
        let dataset;
        let copyDatasetId;
        return this.restClient.getDataset(fromSessionId, datasetId).pipe(
            tap(d => dataset = d),
            tap(() => console.log('copy dataset', dataset.name, ChipsterUtils.toHumanReadable(dataset.size))),
            mergeMap(() => this.restClient.downloadFile(this.originalSessionId, datasetId, localFileName)),
            mergeMap(() => ChipsterUtils.datasetUpload(this.restClient, this.replaySessionId, localFileName, dataset.name)),
            tap(id => copyDatasetId = id),
            mergeMap(copyDatasetId => this.restClient.getDataset(this.replaySessionId, copyDatasetId)),
            mergeMap((copyDataset: any) => {
                copyDataset.metadata = dataset.metadata;
                return this.restClient.putDataset(this.replaySessionId, copyDataset);
            }),
            tap(() => fs.unlinkSync(localFileName)),
            map(() => copyDatasetId),
        );
    }        
}

if (require.main === module) {
	new ReplaySession();
}