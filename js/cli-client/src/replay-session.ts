import { Dataset, Job, Module, Session, Token, Tool } from "chipster-js-common";
import { Logger } from "chipster-nodejs-core";
import * as _ from 'lodash';
import { concat, empty, forkJoin, from, Observable, of } from "rxjs";
import { last } from "rxjs/internal/operators/last";
import { catchError, finalize, map, merge, mergeMap, tap, toArray } from "rxjs/operators";
import ChipsterUtils from "./chipster-utils";
import WsClient from "./ws-client";

const ArgumentParser = require('argparse').ArgumentParser;
const fs = require('fs');
const path = require('path');
const logger = Logger.getLogger(__filename);

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
    static readonly ignoreJobIds = [
        'operation-definition-id-import',
        'operation-definition-id-user-modification',
        'fi.csc.chipster.tools.ngs.LocalNGSPreprocess.java'
    ]

    startTime: Date;
    restClient: any;
    resultsPath: string;
    uploadSessionPrefix: string;
    replaySessionPrefix: string;
    tempPath: string;
    
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
        parser.addArgument(['--parallel', '-P'], { help: 'how many jobs to run in parallel (>1 implies --quiet)', defaultValue: 1 });
        parser.addArgument(['--quiet', '-q'], { help: 'do not print job state changes' , action: 'storeTrue'});
        parser.addArgument(['--results', '-r'], { help: 'replay session prefix and test result directory (both cleared automatically)', defaultValue: 'default-test-set'});
        parser.addArgument(['--temp', '-t'], { help: 'temp directory', defaultValue: 'tmp' });
        parser.addArgument(['--filter', '-F'], { help: 'replay all sessions stored on the server starting with this string', action: 'append'});        
        parser.addArgument(['session'], { help: 'session file or dir to replay', nargs: '?'});
        
        let args = parser.parseArgs();

        this.startTime = new Date();

        const sessionPath = args.session;

        this.resultsPath = args.results;

        /* Session prefixes for recognizing old sessions produced by this job

        Failing cronjobs create easily lots of sessions. We want to delete all old sessions
        created by this cronjob or test set, but don't want to disturb other test sets that 
        might be running at the same time. Most likely the user uses different result directories for
        different test sets, so it's a good value for grouping these temporaray sessions too.
        */
        const testSet = path.basename(args.results); 
        this.uploadSessionPrefix = 'zip-upload/' + testSet + '/';
        //FIXME use this to name the sessions and delete then in the beginning
        //TODO modify OpenShift script to run test sessions and copy test-sessions to the server
        this.replaySessionPrefix = 'replay/' + testSet + '/';
        
        this.tempPath = args.temp;
        let parallel = parseInt(args.parallel);
        if (isNaN(parallel)) {
            throw new Error('the parameter "parallel" is not an integer: ' + args.parallel);
        }

        if (this.resultsPath.length === 0) {
            throw new Error('results path is not set');
        }

        this.mkdirIfMissing(this.resultsPath);

        const originalSessions: any[] = [];
        const quiet = args.quiet || parallel !== 1;

        let importErrors: ImportError[] = [];
        let results = [];

        const fileSessionPlans$ = of(null).pipe(
            mergeMap(() => {
                if (args.session != null) {
                    return from(this.getSessionFiles(args.session));
                } else {
                    return empty();
                }
            }),
            mergeMap((s: string) => {
                return this.uploadSession(s, quiet).pipe(
                    tap((session: Session) => originalSessions.push(session)),
                    mergeMap((session: Session) => {
                        return this.getSessionJobPlans(session, quiet);
                    }),
                    catchError(err => {
                        // unexpected technical problems
                        console.error('session import error', err);
                        importErrors.push({
                            file: s,
                            error: err,
                        });
                        return of([]);
                    }),
                )
            }, null, parallel)
        );

        const serverSessionPlans$ = of(null).pipe(
            mergeMap(() => this.restClient.getSessions()),
            map((sessions: Session[]) => {
                const filtered = [];
                if (args.filter && sessions) {
                    args.filter.forEach(prefix => {
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
            mergeMap((session: Session) => this.getSessionJobPlans(session, quiet)),            
        )

        console.log('login as', args.username);
        ChipsterUtils.login(args.URL, args.username, args.password).pipe(
            mergeMap((token: Token) => ChipsterUtils.getRestClient(args.URL, token.tokenKey)),
            tap(restClient => this.restClient = restClient),
            mergeMap(() => this.deleteOldSessions(this.uploadSessionPrefix, this.replaySessionPrefix)),
            mergeMap(() => this.writeResults([], [], false)),
            mergeMap(() => fileSessionPlans$.pipe(merge(serverSessionPlans$))),
            mergeMap((jobPlans: JobPlan[]) => from(jobPlans)),
            mergeMap(
                (plan: JobPlan) => {
                    return this.replayJob(plan, quiet)
                }, null, parallel),
            tap((sessionResults: any[][]) => {
                results = results.concat(sessionResults);
            }),
            // update results afte each job
            mergeMap(() => this.writeResults(results, importErrors, false)),
            toArray(), // wait for completion and write the final results
            mergeMap(() => this.writeResults(results, importErrors, true)),
            mergeMap(() => {
                const cleanUps = originalSessions.map(u => this.cleanUp(u.originalSessionId, u.replaySessionId, args.debug));
                return concat(...cleanUps).pipe(toArray());
            }),
        ).subscribe(
            () => console.log('session replay done'),
            err => console.error('session replay error', err),
            () => console.log('session replay completed'));
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
            throw new Error('path ' + sessionPath + ' does not exist');
        }
        return sessionFiles;
    }

    deleteOldSessions(nameStart1, nameStart2) {
        return this.restClient.getSessions().pipe(
            map((sessions: Session[]) => {
                return sessions.filter(s => {
                    return s.name.startsWith(nameStart1) || s.name.startsWith(nameStart2);
                })
            }),
            mergeMap((sessions: Session[]) => from(sessions)),
            mergeMap((session: Session) => {
                console.log('delete session', session.name);
                return this.restClient.deleteSession(session.sessionId);
            }),
            toArray(),
        )
    }

    uploadSession(sessionFile: string, quiet: boolean): Observable<any> {
        let name = this.uploadSessionPrefix + path.basename(sessionFile).replace('.zip', '');

        return of(null).pipe(
            tap(() => console.log('upload ' + sessionFile)),            
            mergeMap(() => ChipsterUtils.sessionUpload(this.restClient, sessionFile, name, !quiet)),
            mergeMap(id => this.restClient.getSession(id)),
        );
    }

    getSessionJobPlans(originalSession: Session, quiet: boolean): Observable<JobPlan[]> {        
        let jobSet;
        let datasetIdSet = new Set<string>();
        let replaySessionId: string;

        const originalSessionId = originalSession.sessionId;

        const replaySessionName = this.replaySessionPrefix + path.basename(originalSession.name);

        return of(null).pipe(
            tap(() => {
                if (!quiet) {
                    console.log('create a new session');
                }
            }),
            mergeMap(() => ChipsterUtils.sessionCreate(this.restClient, replaySessionName)),
            tap((id: string) => replaySessionId = id),            
            mergeMap(() => this.restClient.getDatasets(originalSessionId)),
            map((datasets: Dataset[]) => {
                // collect the list of datasets' sourceJobs
                jobSet = new Set(datasets
                    .map(d => d.sourceJob)
                    .filter(id => id != null));
                datasets.forEach(d => datasetIdSet.add(d.datasetId));
            }),
            mergeMap(() => this.restClient.getJobs(originalSessionId)),
            map((jobs: Job[]) => {
                let jobPlans = jobs
                    // run only jobs whose output files exist
                    // and don't care about failed or orphan jobs
                    .filter(j => jobSet.has(j.jobId))
                    // run only jobs that still have at least one input file in the session
                    .filter(j => {
                        for (let i of j.inputs) {
                            if (datasetIdSet.has((<any>i).datasetId)) {
                                return true;
                            }
                        }
                    })
                    // a dummy job of the old Java client
                    .filter(j => ReplaySession.ignoreJobIds.indexOf(j.toolId) === -1)
                    .map(j => {
                        return {
                            originalSessionId: originalSessionId,
                            replaySessionId: replaySessionId,
                            job: j,
                            originalSession: originalSession,
                        }
                    });                
                console.log('session ' + originalSession.name + ' has ' + jobPlans.length + ' jobs');
                return jobPlans;
            }),
        );
    }

    cleanUp(originalSessionId, replaySessionId, debug: boolean) {
                
        return of(null).pipe(
            mergeMap(() => this.restClient.deleteSession(originalSessionId)),
            mergeMap(() => {
                if (debug) {
                    return of(null);
                } else {
                    return this.restClient.deleteSession(replaySessionId);
                }
            }),
        );
    }
    
    replayJob(plan: JobPlan, quiet: boolean): Observable<ReplayResult> {
        
        const job = plan.job;
        const originalSessionId = plan.originalSessionId;
        const replaySessionId = plan.replaySessionId;

        let originalDataset;
        let wsClient;
        const inputMap = new Map<string, Dataset>();
        const parameterMap = new Map<string, any>();
        let replayJobId;

        // why the type isn't recognized after adding the finzalize()?
        return <any>of(null).pipe(
            tap(() => console.log('session ' + plan.originalSession.name + ', replay job ' + job.toolId)),
            mergeMap(() => {
                const fileCopies = job.inputs.map(input => {
                    return this.copyDataset(originalSessionId, replaySessionId, input.datasetId, job.jobId + input.datasetId, quiet).pipe(
                        mergeMap(datasetId => this.restClient.getDataset(replaySessionId, datasetId)),
                        tap((dataset: Dataset) => inputMap.set(input.inputId, dataset)),
                        tap((dataset: Dataset) => {
                            if (!quiet) {
                                console.log('dataset ' + dataset.name + ' copied for input ' + input.inputId);
                            }
                        }),
                    );
                });
                return concat(...fileCopies).pipe(toArray());
            }),
            tap(() => {
                job.parameters.forEach(p => {
                    parameterMap.set(p.parameterId, p.value);
                    if (!quiet) {
                        console.log('set parameter', p.parameterId, p.value)
                    }
                });
            }),
            tap(() => {
                if (!quiet) {
                    console.log('connect websocket');
                }
                wsClient = new WsClient(this.restClient);
                wsClient.connect(replaySessionId, quiet);
            }),
            mergeMap(() => this.restClient.getTool(job.toolId)),
            mergeMap((tool: Tool) => ChipsterUtils.jobRun(this.restClient, replaySessionId, tool, parameterMap, inputMap)),
            mergeMap(jobId => {
                replayJobId = jobId;
                wsClient.getJobScreenOutput$(jobId).subscribe(output => {
                    if (!quiet) {
                        process.stdout.write(output);
                    }
                }, err => {
                    logger.error('failed to get the screen output', err);
                });
                return wsClient.getJobState$(jobId).pipe(
                    tap((job: Job) => {
                        if (!quiet) {
                            console.log('*', job.state, '(' + (job.stateDetail || '') + ')');
                        }
                    }),
                    last(),
                );
            }),
            mergeMap(() => this.compareOutputs(job.jobId, replayJobId, originalSessionId, replaySessionId, plan)),
            catchError(err => {
                // unexpected technical problems
                console.error('replay error', job.toolId, err);
                return of(this.errorToReplayResult(err, job, plan));
            }),
            // why exceptions (like missing tool) interrupt the parallel run if this is before catchError()?
            finalize(() => wsClient.disconnect()),
        );
    }

    errorToReplayResult(err, job: Job, plan: JobPlan): ReplayResult {
        const j = _.clone(job);
        j.state = 'REPLAY_ERROR';
        j.stateDetail = '(see replay logs)';
        j.screenOutput = '';
        return {
            job: j,
            messages: [],
            errors: [err],
            sessionName: plan.originalSession.name,
        };
    }

    compareOutputs(jobId1: string, jobId2: string, sessionId1: string, sessionId2: string, plan: JobPlan): Observable<ReplayResult> {
        let outputs1;
        let outputs2;

        return forkJoin(
            this.restClient.getDatasets(sessionId1),
            this.restClient.getDatasets(sessionId2),).pipe(
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

                    if (WsClient.successStates.indexOf(job2.state) === -1) {
                        errors.push('unsuccessful job state');
                    } else {

                        if (outputs1.length === outputs2.length) {
                            messages.push('correct number of outputs (' + outputs2.length + ')');
                        } else {
                            errors.push('different number of outputs: expected ' + outputs1.length + ' but found ' + outputs2.length);
                        }

                        const names1 = outputs1.map(d => d.name);
                        const names2 = outputs2.map(d => d.name);
                        if (_.isEqual(names1, names2)) {
                            messages.push('correct dataset names');
                        } else {
                            errors.push('different dataset names: expected ' + names1 + ' but found ' + names2);
                        }

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
                    }    
                    return {
                        job: job2,
                        messages: messages,
                        errors: errors,
                        sessionName: plan.originalSession.name,
                    };
            })
        );            
    }
    
    copyDataset(originalSessionId: string, replaySessionId: string, datasetId: string, tempFileName: string, quiet: boolean) {

        
        const localFileName = this.tempPath + '/' + tempFileName;
        let dataset;
        let copyDatasetId;
        
        
        return this.restClient.getDataset(originalSessionId, datasetId).pipe(
            tap(d => dataset = d),
            tap(() => this.mkdirIfMissing(this.tempPath)),
            tap(() => {
                if (!quiet) {
                    console.log('copy dataset', dataset.name, ChipsterUtils.toHumanReadable(dataset.size));
                }
            }),
            mergeMap(() => this.restClient.downloadFile(originalSessionId, datasetId, localFileName)),
            mergeMap(() => ChipsterUtils.datasetUpload(this.restClient, replaySessionId, localFileName, dataset.name)),
            tap(id => copyDatasetId = id),
            mergeMap(copyDatasetId => this.restClient.getDataset(replaySessionId, copyDatasetId)),
            mergeMap((copyDataset: Dataset) => {                
                copyDataset.metadataFiles = dataset.metadataFiles;
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
                if (!fs.lstatSync(filePath).isDirectory() && (file.endsWith('.txt') || (file.endsWith('.html')))) {
                    fs.unlinkSync(filePath);
                }
            });
        }
    }        
    
    writeResults(results, importErrors: ImportError[], isCompleted: boolean): Observable<any> {

        this.removeAllFiles(this.resultsPath);

        let toolIds = new Set();
        let allToolsCount: number;

        return this.restClient.getTools().pipe(
            tap((modules: Array<Module>) => {
                modules.forEach(module => {
                    module.categories.forEach(category => {
                        category.tools.forEach(tool => {
                            toolIds.add(tool.name.id);
                        });
                    });
                });
                allToolsCount = toolIds.size;
            }),  
            tap(() => this.writeResultsSync(results, importErrors, isCompleted, allToolsCount))
        );
    }

        
    writeResultsSync(results, importErrors: ImportError[], isCompleted: boolean, allToolsCount: number) {
        const booleanResults = results
            .map(r => r.job.state === 'COMPLETED' && r.errors.length === 0);
        
        const totalCount = booleanResults.length;
        const okCount = booleanResults.filter(b => b).length;
        const failCount = booleanResults.filter(b => !b).length;
        
        const uniqTestToolsCount = _.uniq(results.map(r => r.job.toolId)).length;
        
        const stream = fs.createWriteStream(this.resultsPath + '/index.html');
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

            let runningState = isCompleted ? 'completed' : 'running';

            if (failCount === 0 && importErrors.length === 0) {
                if (isCompleted) {
                    if (okCount > 0) {
                        stream.write('<h2>Tool tests ' + runningState + ' - <span style="color: green">everything ok!</span></h2>');
                    } else {
                        stream.write('<h2>Tool tests  - <span style="color: orange">not found!</span></h2>');
                    }
                } else {
                    stream.write('<h2>Tool tests ' + runningState + '</h2>');
                }
            } else {
                stream.write('<h2>Tool tests ' + runningState + ' - <span style="color: red">' + failCount + ' tool(s) failed, ' + importErrors.length + ' session(s) with errors</span></h2>');
            }

            stream.write('<h3>Summary</h3>');
            stream.write('<table>');
            stream.write('<tr>');
            stream.write('<td>Results summary</td>');
            stream.write('<td>' + okCount + ' ok, ' + failCount + ' failed, ' + totalCount + ' total</td>');
            stream.write('</tr>');

            stream.write('<tr>');
            stream.write('<td>Tool coverage</td>');
            stream.write('<td>' + uniqTestToolsCount + ' / ' + allToolsCount + '</td>');
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
                    stream.write('<tr><td>' + importError.file + '</td>');
                    stream.write('<td>' + importError.error.message + '</td>');
                    let errFile = Math.random().toString().replace('.', '') + '.txt';
                    stream.write('<td><a href = "' + errFile + '" > Stacktrace </a></td>');
                    // write the sreen output to a separate file
                    const s2 = fs.createWriteStream(this.resultsPath + '/' + errFile);
                    s2.once('open', function (fd) {
                        s2.write(importError.error.message + importError.error.stack);
                        s2.end();
                    });
                    stream.write('</tr>')
                });         
                stream.write('</table>');
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
                if (r.job.screenOutput == null) {
                    stream.write('Screen output is null');    
                } else {
                    stream.write('<a href = "' + r.job.jobId + '.txt" > Screen output (' + ChipsterUtils.toHumanReadable(r.job.screenOutput.length) + ' B) </a>');
                    // write the sreen output to a separate file
                    const s2 = fs.createWriteStream(this.resultsPath + '/' + r.job.jobId + '.txt');
                    s2.once('open', function (fd) {
                        s2.write(r.job.screenOutput);
                        s2.end();
                    });
                }
                stream.write('</td >\n');
                stream.write('<td>' + duration + '</td>\n');
                stream.write('</tr>\n');

            });
            stream.write('</table></body></html>\n');
            stream.end();
        });
        
        // create, update or delete the flag file based on the result
        if (isCompleted) {
            const flagPath = this.resultsPath + '/' + this.flagFile;
            if (failCount === 0 && okCount > 0) {             
                // create the file or update its mtime
                fs.writeFileSync(flagPath, 'test-ok');
            } else {
                if (fs.existsSync(flagPath)) {
                    fs.unlinkSync(flagPath);
                }
            }
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
	new ReplaySession();
}