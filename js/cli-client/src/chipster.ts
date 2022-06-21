#!/usr/bin/env node
import { Dataset, Job, Module, Rule, Service, Session, Tool } from "chipster-js-common";
import { Logger, RestClient } from "chipster-nodejs-core";
import * as _ from 'lodash';
import { empty as observableEmpty, forkJoin, forkJoin as observableForkJoin, from as observableFrom, of as observableOf, Observable, of } from 'rxjs';
import { map, mergeMap, tap, toArray, catchError, finalize, filter } from 'rxjs/operators';
import ChipsterUtils from "./chipster-utils";
import CliEnvironment from "./cli-environment";
import WsClient from "./ws-client";
import { VError } from "verror";

const YAML = require('yamljs');
const path = require('path');
const ArgumentParser = require('argparse').ArgumentParser;
const logger = Logger.getLogger(__filename);
export default class CliClient {

	private restClient: RestClient;
  private env = new CliEnvironment();

  private isQuiet = false;
  private output: string;

	constructor() {
	  this.parseCommand();
	}

	parseCommand() {

    const version = 'Chipster CLI version 0.2.0';

    let parser = new ArgumentParser({
      add_help: true,
      description: 'Chipster command line client for the version 4 and upwards',
    });

    parser.add_argument( '-v', '--version' , { action: 'version', version: version, help: 'show program\'s version nubmer and exit' })

    parser.add_argument('-q', '--quiet', { action: 'store_true', help: 'suppress all extra status info' });

    parser.add_argument('-o', '--output', { choices: ['default', 'json', 'yaml'], help: 'output format. json and yaml enable --quiet'});

    let subparsers = parser.add_subparsers({
      title:'commands',
      dest:"command"
    });

    let sessionSubparsers = subparsers.add_parser('session').add_subparsers({
      title:'session subcommands',
      dest:"subcommand"
    });


    let sessionListSubparser = sessionSubparsers.add_parser('list');

    let sessionGetSubparser = sessionSubparsers.add_parser('get');
    sessionGetSubparser.add_argument('name', { help: 'session name or id' });

    let sessionOpenSubparser = sessionSubparsers.add_parser('open');
    sessionOpenSubparser.add_argument('name', { help: 'session name or id' });

    let sessionDeleteSubparser = sessionSubparsers.add_parser('delete');
    sessionDeleteSubparser.add_argument('name', { help: 'session name or id' });

    let sessionCreateSubparser = sessionSubparsers.add_parser('create');
    sessionCreateSubparser.add_argument('name', { help: 'session name'});

    let sessionUploadSubparser = sessionSubparsers.add_parser('upload');
    sessionUploadSubparser.add_argument('file', { help: 'session file to upload or - for stdin'});
    sessionUploadSubparser.add_argument('--name', { help: 'session name (affects only the old session format)'});

    let sessionDownloadSubparser = sessionSubparsers.add_parser('download');
    sessionDownloadSubparser.add_argument('name', {help: 'session name or id'});
    sessionDownloadSubparser.add_argument('--file', {help: 'file to write or - for stdout'});

    let datasetSubparsers = subparsers.add_parser('dataset').add_subparsers({
      title:'dataset subcommands',
      dest:"subcommand"
    });

    let datasetListSubparser = datasetSubparsers.add_parser('list');

    let datasetGetSubparser = datasetSubparsers.add_parser('get');
    datasetGetSubparser.add_argument('name', {help: 'dataset name or id'});

    let datasetDeleteSubparser = datasetSubparsers.add_parser('delete');
    datasetDeleteSubparser.add_argument('name', {help: 'dataset name or id'});

    let datasetCreateSubparser = datasetSubparsers.add_parser('upload');
    datasetCreateSubparser.add_argument('file', {help: 'file to read or - for stdin'});
    datasetCreateSubparser.add_argument('--name', {help: 'dataset name'});

    let datasetDownloadSubparser = datasetSubparsers.add_parser('download');
    datasetDownloadSubparser.add_argument('name', {help: 'dataset name or id'});
    datasetDownloadSubparser.add_argument('--file', {help: 'file to write or - for stdout'});


    let jobSubparsers = subparsers.add_parser('job').add_subparsers({
      title:'job subcommands',
      dest:"subcommand"
    });

    let jobListSubparser = jobSubparsers.add_parser('list');

    let jobGetSubparser = jobSubparsers.add_parser('get');
    jobGetSubparser.add_argument('name', { help: 'job name or id' });
    
    let jobRunSubparser = jobSubparsers.add_parser('run');
    jobRunSubparser.add_argument('tool', { help: 'tool name or id' });
    jobRunSubparser.add_argument('--input', '-i', { help: 'INPUT_NAME=DATASET_NAME_OR_ID', action: 'append' });
    jobRunSubparser.add_argument('--parameter', '-p', { help: 'PARAMETER_NAME=VALUE', action: 'append' });
    jobRunSubparser.add_argument('--background', '-b', {action: 'store_true', help: 'do not wait'});

    let jobDeleteSubparser = jobSubparsers.add_parser('delete');
    jobDeleteSubparser.add_argument('name', { help: 'job name or id' });
    

    let toolSubparsers = subparsers.add_parser('tool').add_subparsers({
      title:'tool subcommands',
      dest:"subcommand"
    });

    let toolListSubparser = toolSubparsers.add_parser('list');

    let toolGetSubparser = toolSubparsers.add_parser('get');
    toolGetSubparser.add_argument('name', {help: 'tool name or id'});


    let loginSubparser = subparsers.add_parser('login');


    let ruleSubparsers = subparsers.add_parser('rule').add_subparsers({
      title:'access subcommands',
      dest:"subcommand"
    });

    let ruleListSubparser = ruleSubparsers.add_parser('list');

    let ruleCreateSubparser = ruleSubparsers.add_parser('create');
    ruleCreateSubparser.add_argument('username', {help: 'username'});
    ruleCreateSubparser.add_argument('--mode', {help: 'r for read-only, rw for read-write (default)'});

    let ruleDeleteSubparser = ruleSubparsers.add_parser('delete');
    ruleDeleteSubparser.add_argument('username', {help: 'username'});


    let serviceSubparsers = subparsers.add_parser('service').add_subparsers({
      title: 'service admin subcommnads',
      dest: 'subcommand'
    });

    let serviceListSubparser = serviceSubparsers.add_parser('list');

    let serviceGetSubparser = serviceSubparsers.add_parser('get');
    serviceGetSubparser.add_argument('name', { nargs: '?', help: 'service name or id'});


    loginSubparser.add_argument('URL', { nargs: '?', help: 'url of the API server'});
    loginSubparser.add_argument('--username', '-u', { help: 'username for the server'});
    loginSubparser.add_argument('--password', '-p', { help: 'password for the server' });
    loginSubparser.add_argument("--app", "-a", {
      help: "application name"
    });

    subparsers.add_parser('logout');

    let args = parser.parse_args();

    logger.debug(args);

    this.isQuiet = args.quiet;
    this.output = args.output;

    if (this.output === 'json' || this.output === 'yaml') {
      this.isQuiet = true;
    }

    this.restClient = new RestClient(true, null, null, this.isQuiet);

    this.printLoginStatus(args).pipe(
      mergeMap(() => this.runCommand(args)))
      .subscribe(null, err => {
        if (err.code === "EPIPE") {
          // stdout is closed, write to stderr (for example when output is piped to "head")
          console.error(err.message);
        } else {
          this.showError(err);          
        }
        // set exit code but allow possible other asynchronous tasks to complete
        process.exitCode = 1;
      });
  }

  runCommand(args): Observable<any> {
    if (args.command === 'session') {
      switch (args.subcommand) {
        case 'list': return this.sessionList();
        case 'get': return this.sessionGet(args);
        case 'create': return this.sessionCreate(args);
        case 'delete': return this.sessionDelete(args);
        case 'upload': return this.sessionUpload(args);
        case 'download': return this.sessionDownload(args);
        case 'open': return this.sessionOpen(args);
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'dataset') {
      switch (args.subcommand) {
        case 'list': return this.datasetList();
        case 'get': return this.datasetGet(args);
        case 'upload': return this.datasetUpload(args);
        case 'download': return this.datasetDownload(args);
        case 'delete': return this.datasetDelete(args);
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'job') {
      switch (args.subcommand) {
        case 'list': return this.jobList();
        case 'get': return this.jobGet(args);
        case 'run': return this.jobRun(args);
        case 'delete': return this.jobDelete(args);
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'tool') {
      switch (args.subcommand) {
        case 'list': return this.toolList();
        case 'get': return this.toolGet(args);
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'rule') {
      switch (args.subcommand) {
        case 'list': return this.ruleList();
        case 'create': return this.ruleCreate(args);
        case 'delete': return this.ruleDelete(args);
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'service') {
      switch (args.subcommand) {
        case 'list': return this.serviceList();
        case 'get': return this.serviceGet(args);
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'login') {
      return this.login(args).pipe(
        mergeMap(() => this.printLoginStatus(null)));
    } else if (args.command === 'logout') {
      return this.logout().pipe(
        mergeMap(() => this.printLoginStatus(null)));
    } else {
      throw new Error("command not given, see --help");      
    }
  }

  printLoginStatus(args) {

    if (args && (args.command === 'login' || args.command === 'logout' || this.isQuiet)) {
      return observableOf(null);
    } else {

      let uri$ = this.env.get('webServerUri');
      let username$ = this.env.get('username');
      let token$ = this.env.get('token');

      return observableForkJoin(uri$, username$, token$).pipe(tap(res => {
        if (res[2]) {
          console.log('Logged in to ' + res[0] + ' as ' + res[1]);
          console.log();
        } else {
          console.log('Logged out');
        }
      }));
    }
  }

  isLoggedIn() {
    return !!this.restClient.token;
  }

  checkLogin() {
    if (this.isLoggedIn()) {
      return observableOf(this.restClient);
    } else {
      let webServerUrl;
      return this.env.get('webServerUri').pipe(
        tap(url => webServerUrl = url),
        mergeMap(() => this.env.get('token')),
        mergeMap(token => {
          if (!webServerUrl || !token) {
            throw new Error('Login required');
          }
          return ChipsterUtils.configureRestClient(webServerUrl, token, this.restClient);
        }),
        tap(restClient => this.restClient = restClient),
      );
    }
  }

  getSessionId() {
    return this.env.get('sessionId');
  }

  getApp() {
    return this.env.get('app');
  }

  login(args) {

    let webServerUri;
    let username;
    let password;
    let app;

    // get the previous uri and use it as a prompt default
    return this.env.get("webServerUri").pipe(
      mergeMap(defaultUri =>
        args.URL
          ? observableOf(args.URL)
          : ChipsterUtils.getPrompt("server: ", defaultUri)
      ),
      map(webServer => ChipsterUtils.fixUri(webServer)),
      tap(webServer => (webServerUri = webServer)),

      // get the previous username and use it as a prompt default
      mergeMap(() => this.env.get("username")),
      mergeMap(defaultUsername =>
        args.username
          ? observableOf(args.username)
          : ChipsterUtils.getPrompt("username: ", defaultUsername)
      ),
      tap(u => (username = u)),

      // password prompt
      mergeMap(() =>
        args.password
          ? observableOf(args.password)
          : ChipsterUtils.getPrompt("password: ", "", true)
      ),
      tap(p => (password = p)),

      // get the previous app name and use it as a prompt default
      mergeMap(() => this.env.get("app")),
      map(defaultApp => {
        if (defaultApp == null) {
          defaultApp = "chipster";
        }
        return args.app
          ? args.app
          : defaultApp
      }),
      tap(u => (app = u)),

      mergeMap(() => ChipsterUtils.getToken(webServerUri, username, password, this.restClient)),
      // save
      mergeMap((token: string) => this.env.set("token", token)),
      mergeMap(() => this.env.set("webServerUri", webServerUri)),
      mergeMap(() => this.env.set("username", username)),
      mergeMap(() => this.env.set("app", app)),
      mergeMap(() => this.checkLogin())
    );
  }

  logout() {
    return this.env.set('token', null);
  }

  sessionUpload(args) {
    
    let datasetName = path.basename(args.file);
    let sessionName = args.name || datasetName.replace('.zip', '');
    let sessionId;
    let datasetId;

    return this.checkLogin().pipe(
      mergeMap(() => ChipsterUtils.sessionUpload(this.restClient, args.file, sessionName, !this.isQuiet)),
      mergeMap(sessionId => this.setOpenSession(sessionId)),
    );
  }

  sessionDownload(args) {
    let file = args.file || args.name + '.zip';

    return this.getSessionByNameOrId(args.name).pipe(
      mergeMap(session => this.restClient.packageSession(session.sessionId, file))
    );
  }

  sessionList() {
    return this.checkLogin().pipe(      
      mergeMap(() => this.getApp()),
      mergeMap(appId =>
            forkJoin(
              this.restClient.getSessions(),
              this.restClient.getExampleSessions(appId)
            )
          ),
        map(res => {
          const ownSessions = <Session[]>res[0];
          const sharedSessions = <Session[]>res[1];
          const allSessions = ownSessions.concat(sharedSessions);
          return allSessions;
        }),
        tap((sessions: Array<Session>) => {
          const sessionArray = sessions.map(s => [s.name, s.sessionId]);
          this.formatArray(sessions, ['name', 'sessionId'], [50]);
      }));
  }

  sessionGet(args) {
    return this.getSessionByNameOrId(args.name).pipe(
      tap(session => this.formatOutput(session))
    );
  }

  formatOutput(object) {
    if (this.output === 'json') {
      console.log(JSON.stringify(object, null, 4));
    } else if (this.output === 'yaml') {
      console.log(YAML.stringify(object, 4));
    } else {
      console.log(object);
    }
  }

  formatArray(array, keys: string[], widths: number[]) {
    if (this.output === 'json') {
      console.log(JSON.stringify(array, null, 4));
    } else if (this.output === 'yaml') {
      console.log(YAML.stringify(array, 4));
    } else {
      array.forEach(item => ChipsterUtils.printTable(item, keys, widths));
    }
  }

  sessionOpen(args) {
    return this.getSessionByNameOrId(args.name).pipe(
      mergeMap((session: Session) => this.setOpenSession(session.sessionId)),
    );
  }

  setOpenSession(sessionId: string) {
    return this.env.set('sessionId', sessionId);
  }

  ruleList() {
    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      mergeMap(sessionId => this.restClient.getRules(sessionId)),
      tap(list => this.formatOutput(list)),
    );
  }

  ruleCreate(args) {
    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      mergeMap(sessionId => this.restClient.postRule(sessionId, args.username, args.mode !== 'r')),
      tap(res => console.log(res)),
    );
  }

  ruleDelete(args) {
    let sessionId: string;
    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      tap(id => sessionId = id),
      mergeMap(() => this.restClient.getRules(sessionId)),
      mergeMap((rules: Rule[]) => observableFrom(rules.filter(r => r.username === args.username))),
      mergeMap((rule: Rule) => this.restClient.deleteRule(sessionId, rule.ruleId)),
    );
  }

  datasetList() {
    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      mergeMap(sessionId => this.restClient.getDatasets(sessionId)),
      tap((datasets: Array<Dataset>) => {
        this.formatArray(datasets, ['name', 'datasetId'], [50]);
      }),
    );
  }

  showError(err) {
    console.error(err.message);
  }

  datasetGet(args) {
    return this.getDatasetByNameOrId(args.name).pipe(
      tap(dataset => this.formatOutput(dataset))
    );
  }

  datasetUpload(args) {
    let name = args.name || path.basename(args.file);
    let sessionId;
    return this.checkLogin()
      .pipe(
        mergeMap(() => this.getSessionId()),
        tap(id => (sessionId = id)),
        mergeMap(() =>
          ChipsterUtils.datasetUpload(
            this.restClient,
            sessionId,
            args.file,
            name
          )
        )
      );
  }

  datasetDelete(args) {
    let sessionId;
    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      tap(id => sessionId = id),
      mergeMap(() => this.getDatasetByNameOrId(args.name)),
      mergeMap(dataset => this.restClient.deleteDataset(sessionId, dataset.datasetId)),
    );
  }

  datasetDownload(args) {
    let sessionId;
    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      tap(id => sessionId = id),
      mergeMap(() => this.getDatasetByNameOrId(args.name)),
      mergeMap(dataset => {
        let file = args.file || args.name;
        return this.restClient.downloadFile(sessionId, dataset.datasetId, file);
      }),
    );
  }

  jobList() {
    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      mergeMap(sessionId => this.restClient.getJobs(sessionId)),
      tap((jobs: Array<Job>) => {
        this.formatArray(jobs, ['state', 'created', 'toolId', 'jobId'], [10, 25, 32]);        
      }),
    );
  }

  jobGet(args) {
    return this.getJobByNameOrId(args.name).pipe(
      tap(job => this.formatOutput(job))
    );
  }

  jobRun(args) {
    let sessionId, jobId, inputMap;
    let wsClient;

    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      tap(id => sessionId = id),
      tap(() => {
        if (!args.background) {
          wsClient = new WsClient(this.restClient);
          wsClient.connect(sessionId);
        }
      }),
      mergeMap(() => {
        inputMap = ChipsterUtils.parseArgArray(args.input);
        const datasetObservables = Array.from(inputMap.keys()).map(key => {
          return this.getDatasetByNameOrId(inputMap.get(key)).pipe(
            tap(dataset => inputMap.set(key, dataset)));
        });
        if (datasetObservables.length > 0) {
          return observableForkJoin(datasetObservables);
        } else {
          return of(null);
        }
      }),
      mergeMap(() => this.getToolByNameOrId(args.tool)),
      mergeMap(tool => {
        const paramMap = ChipsterUtils.parseArgArray(args.parameter);
        return ChipsterUtils.jobRun(this.restClient, sessionId, tool, paramMap, inputMap);
      }),
      tap(id => jobId = id),
      mergeMap(() => {

        if (!wsClient) {
          this.formatOutput({
            jobId: jobId,
          });
          return of(null);
        }

        const jobState$ = wsClient.getJobState$(jobId).pipe(
          filter((job: Job) => {
            console.log('*', job.state, '(' + (job.stateDetail || '') + ')');
            if (WsClient.failedStates.includes(job.state)) {
              throw new VError(job, "job failed");
            }
            // filter events until the job finishes            
            return false;
          }),
          catchError(err => {
            throw new VError(err, 'failed to get the job state');
          }),
          finalize(() => {
            wsClient.disconnect();
          }),
        )

        wsClient.getJobScreenOutput$(jobId).subscribe(output => {
          process.stdout.write(output);
        }, err => {
          console.log("screen output error", err);
        });

        wsClient.getJobOutputDatasets$(jobId).subscribe(dataset => {
          console.log('* dataset created: ' + dataset.name.padEnd(24) + dataset.datasetId);
        }, err => {
          console.log("output dataset error", err);
        });

        return jobState$;
      }),
    );      
  }

  jobDelete(args) {
    let sessionId;
    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      tap(id => sessionId = id),
      mergeMap(() => this.getJobByNameOrId(args.name)),
      mergeMap(job => this.restClient.deleteJob(sessionId, job.jobId)),
    );
  }

  toolList() {
    // login not needed, but it creates restClient
    return this.checkLogin().pipe(
      mergeMap(() => this.restClient.getTools())).pipe(
        tap((modules: Array<Module>) => {
          if (this.output === 'json' || this.output === 'yaml') {
            // the original objects for structured outputs
            this.formatOutput(modules);
          } else {
            // format here, because formatArray() doesn't handle nested references
            modules.forEach(module => {
              module.categories.forEach(category => {
                category.tools.forEach(tool => {
                  console.log(module['name'].padEnd(12), category.name.padEnd(24), tool.name.id.padEnd(40), tool.name.displayName);
                });
              });
            });
          }
        }),
      );
  }

  toolGet(args) {
    return this.getToolByNameOrId(args.name).pipe(
      tap(tool => this.formatOutput(tool)),
    );
  }

  getSessionByNameOrId(search: string) {
    return this.checkLogin().pipe(
      mergeMap(() => this.restClient.getSessions()),
      map((sessions: Array<Session>) => sessions.filter(s => s.name === search || s.sessionId.startsWith(search))),
      map(sessions => {
        if (sessions.length !== 1) {
          throw new Error('found ' + sessions.length + ' sessions');
        }
        return sessions[0];
      }),);
  }

  getDatasetByNameOrId(search: string) {
    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      mergeMap(sessionId => this.restClient.getDatasets(sessionId)),
      map((datasets: Array<Dataset>) => datasets.filter(d => d.name === search || d.datasetId.startsWith(search))),
      map(datasets => {
        if (datasets.length !== 1) {
          throw new Error('found ' + datasets.length + ' datasets');
        }
        return datasets[0];
      }),);
  }

  getJobByNameOrId(search: string) {
    return this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      mergeMap(sessionId => this.restClient.getJobs(sessionId)),
      map((jobs: Array<Job>) => jobs.filter(j => j.toolId === search || j.jobId.startsWith(search))),
      map(jobs => {
        if (jobs.length !== 1) {
          throw new Error('found ' + jobs.length + ' jobs');
        }
        return jobs[0];
      }),);
  }

  getToolByNameOrId(search: string) {
    // login not needed, but it creates restClient
    return this.checkLogin().pipe(
      mergeMap(() => this.restClient.getTools()),
      map((modules: Module[]) => {
        const categoryArrays = modules.map(module => module.categories);
        const categories = _.flatten(categoryArrays);
        const toolArrays = categories.map(category => category.tools);
        return _.flatten(toolArrays);
      }),
      map((tools: Array<Tool>) => tools.filter(t => {
        return t.name.id === search || t.name.displayName === search;
      })),
      map(tools => {
        if (tools.length !== 1) {
          throw new Error('found ' + tools.length + ' tools');
        }
        return tools[0];
      }),);
  }

  sessionDelete(args) {
    return this.getSessionByNameOrId(args.name).pipe(
      mergeMap(s => this.restClient.deleteSession(s.sessionId)));
  }

  sessionCreate(args) {
    return this.checkLogin().pipe(
      mergeMap(() => ChipsterUtils.sessionCreate(this.restClient, args.name)));
  }

  serviceList() {
    return this.checkLogin().pipe(
      mergeMap(() => this.restClient.getServices()),
      tap(services => this.formatOutput(services)),
    );
  }

  serviceGet(args) {
    return this.checkLogin().pipe(
      mergeMap(() => this.restClient.getInternalServices()),
      // map((services: Array<Service>) => services.filter(s => !!s.publicUri && s.publicUri.startsWith('http'))),
      map((services: Array<Service>) => services.filter(s => !args.name || s.serviceId === args.name || s.role === args.name)),
      mergeMap(services => observableFrom(services)),
      mergeMap(service => {
        return observableForkJoin(
          observableOf(service),
          this.restClient.getStatus(service.adminUri).pipe(
            catchError(err => {
              console.log(service.role + ' error (' + service.publicUri + ')');
              return observableEmpty();
          })));
      }),
      toArray(),
      tap(statuses => {
          if (statuses.length === 1) {
            let res = statuses[0];
            let service = res[0];
            let status = <any>res[1];
            for (let key in status) {
              let value = status[key];
              console.log(service.role + '\t' + service.serviceId+ '\t' + key + '\t' + value + '\t' + ChipsterUtils.toHumanReadable(value));
            }
          } else {
            statuses.forEach(res => {
              console.log(res[0].role, res[1]['status']);
            });
          }
        }),
    );
  }
}

if (require.main === module) {
	new CliClient();
}
