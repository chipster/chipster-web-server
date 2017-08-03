
import {RestClient} from "../../type-service/src/rest-client";
import {Observable, Subject} from "rxjs";
import {Logger} from "../../type-service/src/logger";
import CliEnvironment from "./cli-environment";
import {subscribeOn} from "rxjs/operator/subscribeOn";

const path = require('path');
const read = require('read');
const ArgumentParser = require('argparse').ArgumentParser;
const logger = Logger.getLogger(__filename);

export default class CliClient {

	private restClient = null;
  private env = new CliEnvironment();

	constructor() {
	  this.parseCommand();
	}

	parseCommand() {

    let parser = new ArgumentParser({
      version: '0.0.1',
      addHelp:true,
      description: 'Chipster command line client for the version 4 and upwards',
    });

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


    loginSubparser.addArgument(['URL'], { nargs: '?', help: 'url of the API server'});
    loginSubparser.addArgument(['--username', '-u'], { help: 'username for the server'});
    loginSubparser.addArgument(['--password', '-p'], { help: 'password for the server'});

    subparsers.addParser('logout');

    let args = parser.parseArgs();

    logger.debug(args);

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
    } else if (args.command === 'rule') {
      switch (args.subcommand) {
        case 'list': this.ruleList(); break;
        case 'create': this.ruleCreate(args); break;
        case 'delete': this.ruleDelete(args); break;
        default: throw new Error('unknown subcommand ' + args.subcommand);
      }
    } else if (args.command === 'login') {
      this.login(args);
    } else if (args.command === 'logout') {
      this.logout();
    }
  }

  isLoggedIn() {
    return !!this.restClient;
  }


  checkLogin() {
    if (this.isLoggedIn()) {
      return Observable.of(this.restClient);
    } else {
      let uri$ = this.env.get('serviceLocatorUri');
      let token$ = this.env.get('token');

      return Observable.forkJoin(uri$, token$).map(res => {

        let uri = res[0];
        let token = res[1];

        if (uri && token) {

          // use existing token
          this.restClient = new RestClient(true, token, uri);
          return this.restClient;
        } else {
          throw new Error('Login required');
        }
      });
    }
  }

  getSessionId() {
    return this.env.get('sessionId');
  }

  login(args) {

    let url;
    let username;
    let password;

    let prompts = [];
    let url$ = args.URL ? Observable.of(args.URL) : this.getPrompt('server: ');
    url$
      .flatMap(u => {
        url = u;
        return args.username ? Observable.of(args.username) : this.getPrompt('username: ');
      })
      .flatMap(u => {
        username = u;
        return args.password ? Observable.of(args.password) : this.getPrompt('password: ', true);
      })
      .flatMap(p => {
        password = p;
        let guestClient = new RestClient(true, null, url);
        return guestClient.getToken(username, password)
      })
      .flatMap(token => this.env.set('token', token.tokenKey))
      .flatMap(() => this.env.set('serviceLocatorUri', url))
      .flatMap(() => this.checkLogin())
      .subscribe();
  }

  logout() {
    this.env.clear();
  }

  sessionUpload(args) {

    let datasetName = path.basename(args.file);
    let sessionName = args.name || datasetName.replace('.zip', '');
    let sessionId;
    let datasetId;

    this.checkLogin()
      .flatMap(() => this.restClient.postSession({name: sessionName}))
      .do(id => sessionId = id)
      .flatMap(() => this.restClient.postDataset(sessionId, {name: datasetName}))
      .do(id => datasetId = id)
      .flatMap(datasetId => this.restClient.uploadFile(sessionId, datasetId, args.file))
      .flatMap(() => this.restClient.extractSession(sessionId, datasetId))
      .do((resp) => console.log(resp))
      .flatMap(() => this.restClient.deleteDataset(sessionId, datasetId))
      .flatMap(() => this.setOpenSession(sessionId))
      .subscribe();
  }

  sessionDownload(args) {
    let file = args.file || args.name + '.zip';

    this.getSessionByNameOrId(args.name)
      .flatMap(session => this.restClient.packageSession(session.sessionId, file))
      .subscribe();
  }

  sessionList() {
    this.checkLogin()
      .flatMap(() => this.restClient.getSessions())
      .map((sessions: Array<any>) => sessions.map(s => s.name))
      .subscribe(sessions => {
        sessions.forEach(name => console.log(name))
      });
  }

  sessionGet(args) {
    this.getSessionByNameOrId(args.name)
      .subscribe(session => console.log(session));
  }

  sessionOpen(args) {
    this.getSessionByNameOrId(args.name)
      .flatMap((session: any) => this.setOpenSession(session.sessionId))
      .subscribe();
  }

  setOpenSession(sessionId: string) {
    return this.env.set('sessionId', sessionId);
  }

  ruleList() {
    this.checkLogin()
      .flatMap(() => this.getSessionId())
      .flatMap(sessionId => this.restClient.getRules(sessionId))
      .subscribe(list => console.log(list));
  }

  ruleCreate(args) {
    this.checkLogin()
      .flatMap(() => this.getSessionId())
      .flatMap(sessionId => this.restClient.postRule(sessionId, args.username, args.mode !== 'r'))
      .subscribe(res => console.log(res));
  }

  ruleDelete(args) {
    let sessionId: string;
    this.checkLogin()
      .flatMap(() => this.getSessionId())
      .do(id => sessionId = id)
      .flatMap(() => this.restClient.getRules(sessionId))
      .flatMap((rules: any) => Observable.from(rules.filter(r => r.username === args.username)))
      .flatMap((rule: any) => this.restClient.deleteRule(sessionId, rule.ruleId))
      .subscribe(null, err => console.error('failed to delete the rule', err));
  }

  datasetList() {
    this.checkLogin()
      .flatMap(() => this.getSessionId())
      .flatMap(sessionId => this.restClient.getDatasets(sessionId))
      .map((datasets: Array<any>) => datasets.map(d => d.name))
      .subscribe(datasets => console.log(datasets));
  }

  datasetGet(args) {
    this.getDatasetByNameOrId(args.name)
      .subscribe(dataset => console.log(dataset));
  }

  datasetUpload(args) {
    let name = args.name || path.basename(args.file);
    let sessionId;
    this.checkLogin()
      .flatMap(() => this.getSessionId())
      .do(id => sessionId = id)
      .flatMap(() => this.restClient.postDataset(sessionId, {name: name}))
      .flatMap(datasetId => this.restClient.uploadFile(sessionId, datasetId, args.file))
      .subscribe();
  }

  datasetDelete(args) {
    this.getDatasetByNameOrId(args.name)
      .flatMap(dataset => this.restClient.deleteDataset(dataset.session.sessionId, dataset.datasetId))
      .subscribe();
  }

  datasetDownload(args) {
    this.getDatasetByNameOrId(args.name)
      .flatMap(dataset => {
        let file = args.file || args.name;
        return this.restClient.downloadFile(dataset.session.sessionId, dataset.datasetId, file);
      })
      .subscribe();
  }

  getSessionByNameOrId(search: string) {
    return this.checkLogin()
      .flatMap(() => this.restClient.getSessions())
      .map((sessions: Array<any>) => sessions.filter(s => s.name === search || s.sessionId === search))
      .map(sessions => {
        if (sessions.length !== 1) {
          throw new Error('found ' + sessions.length + ' sessions');
        }
        return sessions[0];
      });
  }

  getDatasetByNameOrId(search: string) {
    return this.checkLogin()
      .flatMap(() => this.getSessionId())
      .flatMap(sessionId => this.restClient.getDatasets(sessionId))
      .map((datasets: Array<any>) => datasets.filter(d => d.name === search || d.datasetId === search))
      .map(datasets => {
        if (datasets.length !== 1) {
          throw new Error('found ' + datasets.length + ' datasets');
        }
        return datasets[0];
      });
  }

  sessionDelete(args) {
    this.getSessionByNameOrId(args.name)
      .flatMap(s => this.restClient.deleteSession(s.sessionId))
      .subscribe();
  }

  sessionCreate(args) {
    this.checkLogin()
      .flatMap(() => this.restClient.postSession({name: args.name}))
      .subscribe();
  }

  getPrompt(prompt, silent = false) {
    let subject = new Subject();

    read({ prompt: prompt, silent: silent}, function(err, line) {
      if (err) {
        subject.error(err);
      }
      subject.next(line);
      subject.complete();
    });

    return subject;
  }
}

if (require.main === module) {
	new CliClient();
}
