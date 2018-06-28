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
 * 
 * First we upload and extract the local session file which allows us to 
 * investigate it with the Rest API. We create a new empty session for the test jobs.
 * We copy (by downloading and uploading) the input files of each job from the original
 * (uploaded) session to this new session and replay the jobs using the same inputs and
 * parameters. Phenodata is copied from the original dataset and there is basic support for
 * multi-inputs. 
 * 
 * One session can have multiple jobs and all of them are run, but the outputs 
 * of the previous jobs are not used for the inputs, but the input files are copied always 
 * from the original session. This may allow better parallelization, but also prevents the test 
 * from noticing if the new outputs are incompatible for the later tools in the workflow. 
 */
export default class ReplaySession {

    readonly RESULTS_PATH = 'results'; 
    readonly TEMP_PATH = 'tmp';
    
    startTime: Date;
    restClient: any;
    //replaySessionId: {};
    //originalSessionId: {};
    //originalSession: any;
    
    constructor() {
        this.parseCommand();
    }
    
    parseCommand() {
        
        let parser = new ArgumentParser({
            version: '0.0.1',
            addHelp: true,
            description: 'Chipster session replay test',
        });
        
        
        parser.addArgument(['URL'], { help: 'url of the app server' });
        parser.addArgument(['--username', '-u'], { help: 'username for the server' });
        parser.addArgument(['--password', '-p'], { help: 'password for the server' });
        parser.addArgument(['--debug', '-d'], { help: 'do not delete the test session', action: 'storeTrue' });
        
        parser.addArgument(['session'], { help: 'session file or dir to replay' });
        
        let args = parser.parseArgs();

        this.startTime = new Date();

        const sessionPath = args.session;
        const sessionFiles = [];
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
            throw new Error('path ' + sessionPath + ' does not exist');
        }
        
        console.log('login as', args.username);
        ChipsterUtils.login(args.URL, args.username, args.password).pipe(
            mergeMap((token: any) => ChipsterUtils.getRestClient(args.URL, token.tokenKey)),
            tap(restClient => this.restClient = restClient),
            mergeMap(() => {
                const replays = sessionFiles.map(s => this.replaySession(s, args.debug));
                return concat(...replays).pipe(toArray());
            }),
            tap((sessionResults: any[][]) => {
                const flatResults = sessionResults.reduce((a, b) => a.concat(b), []);
                this.writeResults(flatResults);
            })
        ).subscribe(
            () => console.log('session replay done'),
            err => console.error('session replay error', err),
            () => console.log('session replay completed'));
    }
    
    replaySession(sessionFile: string, debug: boolean) {

        let jobSet;
        let originalSessionId;
        let replaySessionId;
        let originalSession;
        let results;
                
        return of(null).pipe(
            tap(() => console.log('upload the original sesssion file', sessionFile)),
            mergeMap(() => ChipsterUtils.sessionUpload(this.restClient, sessionFile, null, true)),
            tap(id => originalSessionId = id),
            mergeMap(() => this.restClient.getSession(originalSessionId)),
            tap(session => originalSession = session),
            tap(() => console.log('create a new session')),
            mergeMap(() => ChipsterUtils.sessionCreate(this.restClient, 'session-replay-test')),
            tap(id => replaySessionId = id),
            mergeMap(() => this.restClient.getDatasets(originalSessionId)),
            map((datasets: any[]) => {
                // collect the list of datasets' sourceJobs
                jobSet = new Set(datasets
                    .map(d => d.sourceJob)
                    .filter(id => id != null));
            }),
            mergeMap(() => this.restClient.getJobs(originalSessionId)),
            mergeMap((jobs: any[]) => {
                const replays = jobs
                    // run only jobs whose output files exist
                    // and don't care about failed or orphan jobs
                    .filter(j => jobSet.has(j.jobId))
                    // a dummy job of the old Java client
                    .filter(j => j.toolId !== 'operation-definition-id-import')
                    .map(j => this.replayJob(j, originalSessionId, replaySessionId));
                return concat(...replays).pipe(toArray());
            }),
            tap((r: any[]) => results = r),
            catchError(err => {
                // handle errors here to make sure the sessions are always deleted
                console.error('sesssion replay error', err);
                return of(null);
            }),
            mergeMap(() => this.restClient.deleteSession(originalSessionId)),
            mergeMap(() => {
                if (debug) {
                    return of(null);
                } else {
                    return this.restClient.deleteSession(replaySessionId);
                }
            }),
            map(() => results),
        );
    }
    
    replayJob(job, originalSessionId, replaySessionId) {

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
                    return this.copyDataset(originalSessionId, replaySessionId, input.datasetId).pipe(
                        mergeMap(datasetId => this.restClient.getDataset(replaySessionId, datasetId)),
                        tap(dataset => inputMap.set(input.inputId, dataset)),
                        tap((dataset: any) => console.log('dataset ' + dataset.name + ' copied for input ' + input.inputId)),
                    );
                });
                return concat(...fileCopies).pipe(toArray());
            }),
            tap(() => {
                job.parameters.forEach(p => {
                    parameterMap.set(p.parameterId, p.value);
                    console.log('set parameter', p.parameterId, p.value)
                });
            }),
            tap(() => {
                console.log('connect websocket');
                wsClient = new WsClient(this.restClient);
                wsClient.connect(replaySessionId);
            }),
            mergeMap(() => this.restClient.getTool(job.toolId)),
            mergeMap(tool => ChipsterUtils.jobRun(this.restClient, replaySessionId, tool, parameterMap, inputMap)),
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
            catchError(err => {
                // catch errors to always write results
                console.error('job error', err);
                return of(null);
            }),
            mergeMap(() => this.compareOutputs(job.jobId, replayJobId, originalSessionId, replaySessionId)),            
        );
    }

    compareOutputs(jobId1, jobId2, sessionId1, sessionId2) {
        let outputs1;
        let outputs2;
        let session1;

        return forkJoin(
            this.restClient.getDatasets(sessionId1),
            this.restClient.getDatasets(sessionId2),
            this.restClient.getSession(sessionId1),).pipe(
                map((res: any[]) => {
                    outputs1 = res[0]
                        .filter(d => d.sourceJob === jobId1)
                        .sort((a, b) => a.name.localeCompare(b.name));
                    outputs2 = res[1]
                        .filter(d => d.sourceJob === jobId2)
                        .sort((a, b) => a.name.localeCompare(b.name));
                    session1 = res[2];
                }),
                mergeMap(() => {
                    if (jobId2 != null) {
                        return this.restClient.getJob(sessionId2, jobId2);
                    } else {
                        // create a dummy job if there was an error when creating or POSTing the job
                        return this.restClient.getJob(sessionId1, jobId1).pipe(
                            map((j: any) => {
                                j.state = 'REPLAY_ERROR';
                                j.stateDetail = '(see replay logs)';
                                j.screenOutput = '';
                                return j;
                            }),
                        );
                    }
                }),
                map(job2 => {
                    const errors = [];
                    const messages = [];

                    if (outputs1.length !== outputs2.length) {
                        errors.push('different number of outputs: expected ' +  outputs1.length + ' but found ' + outputs2.length);
                    }
                    messages.push('correct number of outputs (' + outputs2.length + ')');

                    const names1 = outputs1.map(d => d.name);
                    const names2 = outputs2.map(d => d.name);
                    if (!_.isEqual(names1, names2)) {
                        errors.push('different dataset names: expected ' + names1 + ' but found ' + names2);
                    }
                    messages.push('correct dataset names');

                    for (let i = 0; i < Math.min(outputs1.length, outputs2.length); i++) {
                        const d1 = outputs1[i];
                        const d2 = outputs2[i];
                        const sizeDiff = (d2.size - d1.size) / (1.0 * d1.size) * 100;
                        if (d1.size === d2.size) {
                            messages.push('the size of the dataset "' + d1.name + '" is correct (' + ChipsterUtils.toHumanReadable(d2.size) + ')');
                        } else if (Math.abs(sizeDiff) < 30) { // percent
                            messages.push('the size of the dataset "' + d1.name + '" is close enough (' + Math.round(sizeDiff) + '%)');
                        } else {
                            errors.push('the size of the dataset "' + d1.name + '" differs too much (' + Math.round(sizeDiff) + '%)');
                        }
                    }
                    return {
                        job: job2,
                        messages: messages,
                        errors: errors,
                        sessionName: session1.name,
                    };
            })
        );            
    }
    
    copyDataset(originalSessionId, replaySessionId, datasetId) {

        this.mkdirIfMissing(this.TEMP_PATH);

        const localFileName = this.TEMP_PATH + '/' + datasetId;
        let dataset;
        let copyDatasetId;


        return this.restClient.getDataset(originalSessionId, datasetId).pipe(
            tap(d => dataset = d),
            tap(() => console.log('copy dataset', dataset.name, ChipsterUtils.toHumanReadable(dataset.size))),
            mergeMap(() => this.restClient.downloadFile(originalSessionId, datasetId, localFileName)),
            mergeMap(() => ChipsterUtils.datasetUpload(this.restClient, replaySessionId, localFileName, dataset.name)),
            tap(id => copyDatasetId = id),
            mergeMap(copyDatasetId => this.restClient.getDataset(replaySessionId, copyDatasetId)),
            mergeMap((copyDataset: any) => {
                copyDataset.metadata = dataset.metadata;
                return this.restClient.putDataset(replaySessionId, copyDataset);
            }),
            tap(() => fs.unlinkSync(localFileName)),
            map(() => copyDatasetId),
        );
    }   

    mkdirIfMissing(path: string) {
        try {
            fs.mkdirSync(path);
        } catch (err) {
            if (err.code !== 'EEXIST') {
                throw err;
            }
        }
    }

    removeAllFiles(path: string) {     
        if (fs.existsSync(path)) {
            fs.readdirSync(path).forEach((file, index) => {
                var filePath = path + "/" + file;
                if (!fs.lstatSync(filePath).isDirectory()) {
                    fs.unlinkSync(filePath);
                }
            });
        }
    }        
    
    writeResults(results) {

        const booleanResults = results
            .map(r => r.job.state === 'COMPLETED' && r.errors.length === 0);
        
        const totalCount = booleanResults.length;
        const okCount = booleanResults.filter(b => b).length;
        const failCount = booleanResults.filter(b => !b).length;

        const uniqToolsCount = _.uniq(results.map(r => r.job.toolId)).length;

        this.removeAllFiles(this.RESULTS_PATH);
        this.mkdirIfMissing(this.RESULTS_PATH);
        
        const stream = fs.createWriteStream(this.RESULTS_PATH + '/index.html');
        stream.once('open', fd => {
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

            if (failCount === 0) {
                stream.write('<h2>Tool tests - <span style="color: green">everything ok!</span></h2>');
            } else {
                stream.write('<h2>Tool tests - <span style="color: red">' + failCount + ' tool(s) failed</span></h2>');
            }

            stream.write('<h3>Summary</h3>');
            stream.write('<table>');
            stream.write('<tr>');
            stream.write('<td>Results summary</td>');
            stream.write('<td>' + okCount + ' ok, ' + failCount + ' failed, ' + totalCount + ' total</td>');
            stream.write('</tr>');

            stream.write('<tr>');
            stream.write('<td>Tool coverage</td>');
            stream.write('<td>' + uniqToolsCount + '</td>');
            stream.write('</tr>');

            stream.write('<tr>');
            stream.write('<td>Total time</td>');
            stream.write('<td>' + this.millisecondsToHumanReadable(this.dateDiff(this.startTime, new Date())) + '</td>');
            stream.write('</tr>');
        
            stream.write('<tr>');
            stream.write('<td>Start time</td>');
            stream.write('<td>' + this.startTime + '</td>');
            stream.write('</tr>');
            stream.write('</table>');

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
                stream.write('<tr>\n');
                const errorClass = ' class="error-cell"';
                const stateOk = r.job.state === 'COMPLETED';
                const errorsOk = r.errors.length === 0;
                const stateStyle = stateOk ? '' : errorClass;
                const errorsStyle = errorsOk ? '' : errorClass;
                const duration = this.millisecondsToHumanReadable(this.dateDiff(r.job.startTime, r.job.endTime));
                
                stream.write('<td>' + r.sessionName + '</td>\n');
                stream.write('<td>' + r.job.toolId + '</td>\n');
                stream.write('<td' + stateStyle + '>' + r.job.state + '</td>\n');
                stream.write('<td>' + r.messages.join('<br>') + '</td>\n');
                stream.write('<td' + errorsStyle + '>' + r.errors.join('<br>') + '</td>\n');
                stream.write('<td>');
                stream.write(r.job.stateDetail + '<br>');
                stream.write('<a href = "' + r.job.jobId + '.txt" > Screen output (' + ChipsterUtils.toHumanReadable(r.job.screenOutput.length) + ' B) </a>');
                stream.write('</td >\n');
                stream.write('<td>' + duration + '</td>\n');
                stream.write('</tr>\n');

                const s2 = fs.createWriteStream('results/' + r.job.jobId + '.txt');
                s2.once('open', function (fd) {
                    s2.write(r.job.screenOutput);
                    s2.end();
                });
            });
            stream.write('</table></body></html>\n');
            stream.end();
        });
    }

    dateDiff(start, end) {
        return Date.parse(end) - Date.parse(start);
    }

    millisecondsToHumanReadable(ms) {

        const hours = ms / (1000 * 60 * 60);
        const intHours = Math.floor(hours);

        // get remainder from hours and convert to minutes
        const minutes = (hours - intHours) * 60;
        const intMinutes = Math.floor(minutes);

        // get remainder from minutes and convert to seconds
        var seconds = (minutes - intMinutes) * 60;
        var intSeconds = Math.floor(seconds);

        if (intHours > 0) {
            return intHours + 'h ' + intMinutes + 'm';
        } else if (intMinutes > 0) {
            return intMinutes + 'm ' + intSeconds + 's';
        } else {
            return intSeconds + 's';
        }
    }
}

if (require.main === module) {
	new ReplaySession();
}