#!/usr/bin/env node
import { Dataset, Job, Module, Rule, Service, Session, Tool } from "chipster-js-common";
import { Logger, RestClient } from "chipster-nodejs-core";
import * as _ from 'lodash';
import { empty as observableEmpty, forkJoin, forkJoin as observableForkJoin, from as observableFrom, of as observableOf } from 'rxjs';
import { map, mergeMap, tap, toArray } from 'rxjs/operators';
import ChipsterUtils from "./chipster-utils";
import CliEnvironment from "./cli-environment";
import WsClient from "./ws-client";



const path = require('path');
const ArgumentParser = require('argparse').ArgumentParser;
const logger = Logger.getLogger(__filename);


export default class CliClient {

	private restClient = new RestClient();
  private env = new CliEnvironment();

	constructor() {
	  this.parseCommand();
	}

	parseCommand() {

    let parser = new ArgumentParser({
      version: '0.1.5',
      addHelp:true,
      description: 'Chipster command line client for the version 4 and upwards',
    });

    parser.addArgument(['--quiet', '-q'], { nargs: 0, help: 'suppress all extra status info' });

    let subparsers = parser.addSubparsers({
      title:'commands',
      dest:"command"
    });

    let sessionSubparsers = subparsers.addParser('session').addSubparsers({
      title:'session subcommands',
      dest:"subcommand"
    });


    let sessionListSubparser = sessionSubparsers.addParser('list');

    let sessionGetSubparser = sessionSubparsers.addParser('get');
    sessionGetSubparser.addArgument([ 'name' ], { help: 'session name or id' });

    let sessionOpenSubparser = sessionSubparsers.addParser('open');
    sessionOpenSubparser.addArgument([ 'name' ], { help: 'session name or id' });

    let sessionDeleteSubparser = sessionSubparsers.addParser('delete');
    sessionDeleteSubparser.addArgument([ 'name' ], { help: 'session name or id' });

    let sessionCreateSubparser = sessionSubparsers.addParser('create');
    sessionCreateSubparser.addArgument([ 'name' ], { help: 'session name'});

    let sessionUploadSubparser = sessionSubparsers.addParser('upload');
    sessionUploadSubparser.addArgument([ 'file' ], { help: 'session file to upload or - for stdin'});
    sessionUploadSubparser.addArgument([ '--name' ], { help: 'session name (affects only the old session format)'});

    let sessionDownloadSubparser = sessionSubparsers.addParser('download');
    sessionDownloadSubparser.addArgument(['name'], {help: 'session name or id'});
    sessionDownloadSubparser.addArgument(['--file'], {help: 'file to write or - for stdout'});

    let datasetSubparsers = subparsers.addParser('dataset').addSubparsers({
      title:'dataset subcommands',
      dest:"subcommand"
    });

    let datasetListSubparser = datasetSubparsers.addParser('list');

    let datasetGetSubparser = datasetSubparsers.addParser('get');
    datasetGetSubparser.addArgument(['name'], {help: 'dataset name or id'});

    let datasetDeleteSubparser = datasetSubparsers.addParser('delete');
    datasetDeleteSubparser.addArgument(['name'], {help: 'dataset name or id'});

    let datasetCreateSubparser = datasetSubparsers.addParser('upload');
    datasetCreateSubparser.addArgument(['file'], {help: 'file to read or - for stdin'});
    datasetCreateSubparser.addArgument(['--name'], {help: 'dataset name'});

    let datasetDownloadSubparser = datasetSubparsers.addParser('download');
    datasetDownloadSubparser.addArgument(['name'], {help: 'dataset name or id'});
    datasetDownloadSubparser.addArgument(['--file'], {help: 'file to write or - for stdout'});


    let jobSubparsers = subparsers.addParser('job').addSubparsers({
      title:'job subcommands',
      dest:"subcommand"
    });

    let jobListSubparser = jobSubparsers.addParser('list');

    let jobGetSubparser = jobSubparsers.addParser('get');
    jobGetSubparser.addArgument(['name'], { help: 'job name or id' });
    
    let jobRunSubparser = jobSubparsers.addParser('run');
    jobRunSubparser.addArgument(['tool'], { help: 'tool name or id' });
    jobRunSubparser.addArgument(['--input', '-i'], { help: 'INPUT_NAME=DATASET_NAME_OR_ID', action: 'append' });
    jobRunSubparser.addArgument(['--parameter', '-p'], { help: 'PARAMETER_NAME=VALUE', action: 'append' });
    jobRunSubparser.addArgument(['--background', '-b'], {help: 'do not wait'});

    let jobDeleteSubparser = jobSubparsers.addParser('delete');
    jobDeleteSubparser.addArgument(['name'], { help: 'job name or id' });
    

    let toolSubparsers = subparsers.addParser('tool').addSubparsers({
      title:'tool subcommands',
      dest:"subcommand"
    });

    let toolListSubparser = toolSubparsers.addParser('list');

    let toolGetSubparser = toolSubparsers.addParser('get');
    toolGetSubparser.addArgument(['name'], {help: 'tool name or id'});


    let loginSubparser = subparsers.addParser('login');


    let ruleSubparsers = subparsers.addParser('rule').addSubparsers({
      title:'access subcommands',
      dest:"subcommand"
    });

    let ruleListSubparser = ruleSubparsers.addParser('list');

    let ruleCreateSubparser = ruleSubparsers.addParser('create');
    ruleCreateSubparser.addArgument(['username'], {help: 'username'});
    ruleCreateSubparser.addArgument(['--mode'], {help: 'r for read-only, rw for read-write (default)'});

    let ruleDeleteSubparser = ruleSubparsers.addParser('delete');
    ruleDeleteSubparser.addArgument(['username'], {help: 'username'});


    let serviceSubparsers = subparsers.addParser('service').addSubparsers({
      title: 'service admin subcommnads',
      dest: 'subcommand'
    });

    let serviceListSubparser = serviceSubparsers.addParser('list');

    let serviceGetSubparser = serviceSubparsers.addParser('get');
    serviceGetSubparser.addArgument(['name'], { nargs: '?', help: 'service name or id'});


    loginSubparser.addArgument(['URL'], { nargs: '?', help: 'url of the API server'});
    loginSubparser.addArgument(['--username', '-u'], { help: 'username for the server'});
    loginSubparser.addArgument(['--password', '-p'], { help: 'password for the server' });
    loginSubparser.addArgument(["--app", "-a"], {
      help: "application name"
    });

    subparsers.addParser('logout');

    let args = parser.parseArgs();

    logger.debug(args);

    this.printLoginStatus(args).pipe(
      tap(() => this.runCommand(args)))
      .subscribe();
  }

  runCommand(args) {
    if (args.command === 'session') {
      switch (args.subcommand) {
        case 'list': this.sessionList(); break;
        case 'get': this.sessionGet(args); break;
        case 'create': this.sessionCreate(args); break;
        case 'delete': this.sessionDelete(args); break;
        case 'upload': this.sessionUpload(args); break;
        case 'download': this.sessionDownload(args); break;
        case 'open': this.sessionOpen(args); break;
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'dataset') {
      switch (args.subcommand) {
        case 'list': this.datasetList(); break;
        case 'get': this.datasetGet(args); break;
        case 'upload': this.datasetUpload(args); break;
        case 'download': this.datasetDownload(args); break;
        case 'delete': this.datasetDelete(args); break;
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'job') {
      switch (args.subcommand) {
        case 'list': this.jobList(); break;
        case 'get': this.jobGet(args); break;
        case 'run': this.jobRun(args); break;
        case 'delete': this.jobDelete(args); break;
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'tool') {
      switch (args.subcommand) {
        case 'list': this.toolList(); break;
        case 'get': this.toolGet(args); break;
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'rule') {
      switch (args.subcommand) {
        case 'list': this.ruleList(); break;
        case 'create': this.ruleCreate(args); break;
        case 'delete': this.ruleDelete(args); break;
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'service') {
      switch (args.subcommand) {
        case 'list': this.serviceList(); break;
        case 'get': this.serviceGet(args); break;
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'login') {
      this.login(args).pipe(
        mergeMap(() => this.printLoginStatus(null)))
        .subscribe()
    } else if (args.command === 'logout') {
      this.logout().pipe(
        mergeMap(() => this.printLoginStatus(null)))
        .subscribe();
    }
  }

  printLoginStatus(args) {

    if (args && (args.command === 'login' || args.command === 'logout' || args.quiet)) {
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
      mergeMap(defaultApp => {
        if (defaultApp == null) {
          defaultApp = "chipster";
        }
        return args.application
          ? observableOf(args.application)
          : ChipsterUtils.getPrompt("application: ", defaultApp)
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

    this.checkLogin().pipe(
      mergeMap(() => ChipsterUtils.sessionUpload(this.restClient, args.file, sessionName, !args.quiet)),
      mergeMap(sessionId => this.setOpenSession(sessionId)),)
      .subscribe();
  }

  sessionDownload(args) {
    let file = args.file || args.name + '.zip';

    this.getSessionByNameOrId(args.name).pipe(
      mergeMap(session => this.restClient.packageSession(session.sessionId, file)))
      .subscribe();
  }

  sessionList() {
    this.checkLogin().pipe(      
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
        }))
      .subscribe((sessions: Array<Session>) => {
        sessions.forEach(s => console.log(s.name.padEnd(50), s.sessionId))
      });
  }

  sessionGet(args) {
    this.getSessionByNameOrId(args.name)
      .subscribe(session => console.log(session));
  }

  sessionOpen(args) {
    this.getSessionByNameOrId(args.name).pipe(
      mergeMap((session: Session) => this.setOpenSession(session.sessionId)))
      .subscribe();
  }

  setOpenSession(sessionId: string) {
    return this.env.set('sessionId', sessionId);
  }

  ruleList() {
    this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      mergeMap(sessionId => this.restClient.getRules(sessionId)),)
      .subscribe(list => console.log(list));
  }

  ruleCreate(args) {
    this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      mergeMap(sessionId => this.restClient.postRule(sessionId, args.username, args.mode !== 'r')),)
      .subscribe(res => console.log(res));
  }

  ruleDelete(args) {
    let sessionId: string;
    this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      tap(id => sessionId = id),
      mergeMap(() => this.restClient.getRules(sessionId)),
      mergeMap((rules: Rule[]) => observableFrom(rules.filter(r => r.username === args.username))),
      mergeMap((rule: Rule) => this.restClient.deleteRule(sessionId, rule.ruleId)),)
      .subscribe(null, err => console.error('failed to delete the rule', err));
  }

  datasetList() {
    this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      mergeMap(sessionId => this.restClient.getDatasets(sessionId)),)
      .subscribe((datasets: Array<Dataset>) => {
        datasets.forEach(d => console.log(d.name.padEnd(50), d.datasetId))
      });
  }

  datasetGet(args) {
    this.getDatasetByNameOrId(args.name)
      .subscribe(dataset => console.log(dataset));
  }

  datasetUpload(args) {
    let name = args.name || path.basename(args.file);
    let sessionId;
    this.checkLogin()
      .pipe(
        mergeMap(() => this.getSessionId()),
        tap(id => (sessionId = id)),

        //FIXME remove this debug test
        // mergeMap(() => this.restClient.getDatasets(sessionId)),
        // mergeMap(datasets =>
        //   this.restClient.downloadFile(
        //     sessionId,
        //     datasets[0].datasetId,
        //     "tmp/kmans.tsv"
        //   )
        // ),
        mergeMap(() =>
          ChipsterUtils.datasetUpload(
            this.restClient,
            sessionId,
            args.file,
            name
          )
        )
      )
      .subscribe(null, err => console.error("upload error", err));
  }

  datasetDelete(args) {
    let sessionId;
    this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      tap(id => sessionId = id),
      mergeMap(() => this.getDatasetByNameOrId(args.name)),
      mergeMap(dataset => this.restClient.deleteDataset(sessionId, dataset.datasetId)),)
      .subscribe();
  }

  datasetDownload(args) {
    let sessionId;
    this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      tap(id => sessionId = id),
      mergeMap(() => this.getDatasetByNameOrId(args.name)),
      mergeMap(dataset => {
        let file = args.file || args.name;
        return this.restClient.downloadFile(sessionId, dataset.datasetId, file);
      }),)
      .subscribe();
  }

  jobList() {
    this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      mergeMap(sessionId => this.restClient.getJobs(sessionId)),)
      .subscribe((jobs: Array<Job>) => {
        jobs.forEach(j => ChipsterUtils.printTable(j, ['state', 'created', 'toolId', 'jobId'], [10, 25, 32]));
      });
  }

  jobGet(args) {
    this.getJobByNameOrId(args.name)
      .subscribe(job => console.log(job));
  }

  jobRun(args) {
    let sessionId, jobId, inputMap;
    let wsClient;

    this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      tap(id => sessionId = id),
      tap(() => {
        wsClient = new WsClient(this.restClient);
        wsClient.connect(sessionId);
      }),
      mergeMap(() => {
        inputMap = ChipsterUtils.parseArgArray(args.input);
        const datasetObservables = Array.from(inputMap.keys()).map(key => {
          return this.getDatasetByNameOrId(inputMap.get(key)).pipe(
            tap(dataset => inputMap.set(key, dataset)));
        });
        return observableForkJoin(datasetObservables);
      }),
      mergeMap(() => this.getToolByNameOrId(args.tool)),
      mergeMap(tool => {
        const paramMap = ChipsterUtils.parseArgArray(args.parameter);
        return ChipsterUtils.jobRun(this.restClient, sessionId, tool, paramMap, inputMap);
      }),
      tap(id => jobId = id),
      tap(() => {
        wsClient.getJobState$(jobId).subscribe(job => {
          console.log('*', job.state, '(' + (job.stateDetail || '') + ')');
        }, err => {
          console.error('failed to get the job state', err);
        }, () => {
          wsClient.disconnect();
        });
        wsClient.getJobScreenOutput$(jobId).subscribe(output => {
          process.stdout.write(output);
        });
        wsClient.getJobOutputDatasets$(jobId).subscribe(dataset => {
          console.log('* dataset created: ' + dataset.name.padEnd(24) + dataset.datasetId);
        });
      }),)
      .subscribe();
  }

  jobDelete(args) {
    let sessionId;
    this.checkLogin().pipe(
      mergeMap(() => this.getSessionId()),
      tap(id => sessionId = id),
      mergeMap(() => this.getJobByNameOrId(args.name)),
      mergeMap(job => this.restClient.deleteJob(sessionId, job.jobId)),)
      .subscribe();
  }

  toolList() {
    // login not needed, but it creates restClient
    this.checkLogin().pipe(
      mergeMap(() => this.restClient.getTools()))
      .subscribe((modules: Array<Module>) => {
        modules.forEach(module => {
          module.categories.forEach(category => {
            category.tools.forEach(tool => {
              console.log(module['name'].padEnd(12), category.name.padEnd(24), tool.name.id.padEnd(40), tool.name.displayName);
            });
          });
        });
      });
  }

  toolGet(args) {
    this.getToolByNameOrId(args.name)
      .subscribe(tool => console.log(JSON.stringify(tool, null, 2)));
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
    this.getSessionByNameOrId(args.name).pipe(
      mergeMap(s => this.restClient.deleteSession(s.sessionId)))
      .subscribe();
  }

  sessionCreate(args) {
    this.checkLogin().pipe(
      mergeMap(() => ChipsterUtils.sessionCreate(this.restClient, args.name)))
      .subscribe();
  }

  serviceList() {
    this.checkLogin().pipe(
      mergeMap(() => this.restClient.getServices()))
      .subscribe(
        services => console.log(services),
        err => console.error('failed to list services', err))
  }

  serviceGet(args) {
    this.checkLogin().pipe(
      mergeMap(() => this.restClient.getServices()),
      map((services: Array<Service>) => services.filter(s => !!s.publicUri && s.publicUri.startsWith('http'))),
      map((services: Array<Service>) => services.filter(s => !args.name || s.serviceId === args.name || s.role === args.name)),
      mergeMap(services => observableFrom(services)),
      mergeMap(service => {
        return observableForkJoin(
          observableOf(service),
          this.restClient.getStatus(service.publicUri)
            .catch(err => {
              console.log(service.role + ' error (' + service.publicUri + ')');
              return observableEmpty();
        }));
      }),
      toArray(),)
      .subscribe(statuses => {
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
        },
          err => console.error('failed to list services', err)
      );
  }
}

if (require.main === module) {
	new CliClient();
}
