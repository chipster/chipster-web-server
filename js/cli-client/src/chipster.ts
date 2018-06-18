
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
import {RestClient} from "../../type-service/src/rest-client";

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
    loginSubparser.addArgument(['--password', '-p'], { help: 'password for the server'});

    subparsers.addParser('logout');

    let args = parser.parseArgs();

    logger.debug(args);

    this.printLoginStatus(args)
      .do(() => this.runCommand(args))
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
      this.login(args)
        .mergeMap(() => this.printLoginStatus(null))
        .subscribe()
    } else if (args.command === 'logout') {
      this.logout()
        .mergeMap(() => this.printLoginStatus(null))
        .subscribe();
    }
  }

  printLoginStatus(args) {

    if (args && (args.command === 'login' || args.command === 'logout' || args.quiet)) {
      return Observable.of(null);
    } else {

      let uri$ = this.env.get('webServerUri');
      let username$ = this.env.get('username');
      let token$ = this.env.get('token');

      return Observable.forkJoin(uri$, username$, token$).do(res => {
        if (res[2]) {
          console.log('Logged in to ' + res[0] + ' as ' + res[1]);
          console.log();
        } else {
          console.log('Logged out');
        }
      });
    }
  }

  isLoggedIn() {
    return !!this.restClient;
  }


  checkLogin() {
    if (this.isLoggedIn()) {
      return Observable.of(this.restClient);
    } else {
      let uri$ = this.env.get('webServerUri')
        .mergeMap(webServerUrl => new RestClient(true, null, null).getServiceLocator(webServerUrl));

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

    let webServerUri;
    let username;
    let password;

    // get the previous uri and use it as a prompt default
    return this.env.get('webServerUri')
      .mergeMap(defaultUri => args.URL ? Observable.of(args.URL) : this.getPrompt('server: ', defaultUri))
      .map(webServer => this.fixUri(webServer))
      .do(webServer => webServerUri = webServer)

      // get the previous username and use it as a prompt default
      .mergeMap(() => this.env.get('username'))
      .mergeMap(defaultUsername => args.username ? Observable.of(args.username) : this.getPrompt('username: ', defaultUsername))
      .do(u => username = u)

      // password prompt
      .mergeMap(() => args.password ? Observable.of(args.password) : this.getPrompt('password: ', '', true))
      .do(p => password = p)

      // get the service locator address
      .mergeMap(() => new RestClient(true, null, null).getServiceLocator(webServerUri))
      // get token
      .mergeMap(serviceLocatorUrl => {
        let guestClient = new RestClient(true, null, serviceLocatorUrl);
        return guestClient.getToken(username, password)
      })

      // save
      .mergeMap(token => this.env.set('token', token.tokenKey))
      .mergeMap(() => this.env.set('webServerUri', webServerUri))
      .mergeMap(() => this.env.set('username', username))
      .mergeMap(() => this.checkLogin());
  }

  fixUri(uri) {
    if (!uri.startsWith('http')) {
      // add protocol
      uri = 'http://' + uri;
    }
    if (uri.endsWith('/')) {
      // remove trailing slash
      uri = uri.slice(0, -1);
    }
    return uri;
  }

  logout() {
    return this.env.set('token', null);
  }

  sessionUpload(args) {

    let datasetName = path.basename(args.file);
    let sessionName = args.name || datasetName.replace('.zip', '');
    let sessionId;
    let datasetId;

    this.checkLogin()
      .mergeMap(() => this.restClient.postSession({name: sessionName}))
      .do(id => sessionId = id)
      .mergeMap(() => this.restClient.postDataset(sessionId, {name: datasetName}))
      .do(id => datasetId = id)
      .mergeMap(datasetId => this.restClient.uploadFile(sessionId, datasetId, args.file))
      .mergeMap(() => this.restClient.extractSession(sessionId, datasetId))
      .do((resp) => console.log(resp))
      .mergeMap(() => this.restClient.deleteDataset(sessionId, datasetId))
      .mergeMap(() => this.setOpenSession(sessionId))
      .subscribe();
  }

  sessionDownload(args) {
    let file = args.file || args.name + '.zip';

    this.getSessionByNameOrId(args.name)
      .mergeMap(session => this.restClient.packageSession(session.sessionId, file))
      .subscribe();
  }

  sessionList() {
    this.checkLogin()
      .mergeMap(() => this.restClient.getSessions())
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
      .mergeMap((session: any) => this.setOpenSession(session.sessionId))
      .subscribe();
  }

  setOpenSession(sessionId: string) {
    return this.env.set('sessionId', sessionId);
  }

  ruleList() {
    this.checkLogin()
      .mergeMap(() => this.getSessionId())
      .mergeMap(sessionId => this.restClient.getRules(sessionId))
      .subscribe(list => console.log(list));
  }

  ruleCreate(args) {
    this.checkLogin()
      .mergeMap(() => this.getSessionId())
      .mergeMap(sessionId => this.restClient.postRule(sessionId, args.username, args.mode !== 'r'))
      .subscribe(res => console.log(res));
  }

  ruleDelete(args) {
    let sessionId: string;
    this.checkLogin()
      .mergeMap(() => this.getSessionId())
      .do(id => sessionId = id)
      .mergeMap(() => this.restClient.getRules(sessionId))
      .mergeMap((rules: any) => Observable.from(rules.filter(r => r.username === args.username)))
      .mergeMap((rule: any) => this.restClient.deleteRule(sessionId, rule.ruleId))
      .subscribe(null, err => console.error('failed to delete the rule', err));
  }

  datasetList() {
    this.checkLogin()
      .mergeMap(() => this.getSessionId())
      .mergeMap(sessionId => this.restClient.getDatasets(sessionId))
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
      .mergeMap(() => this.getSessionId())
      .do(id => sessionId = id)
      .mergeMap(() => this.restClient.postDataset(sessionId, {name: name}))
      .mergeMap(datasetId => this.restClient.uploadFile(sessionId, datasetId, args.file))
      .subscribe();
  }

  datasetDelete(args) {
    let sessionId;
    this.checkLogin()
      .mergeMap(() => this.getSessionId())
      .do(id => sessionId = id)
      .mergeMap(() => this.getDatasetByNameOrId(args.name))
      .mergeMap(dataset => this.restClient.deleteDataset(sessionId, dataset.datasetId))
      .subscribe();
  }

  datasetDownload(args) {
    let sessionId;
    this.checkLogin()
      .mergeMap(() => this.getSessionId())
      .do(id => sessionId = id)
      .mergeMap(() => this.getDatasetByNameOrId(args.name))
      .mergeMap(dataset => {
        let file = args.file || args.name;
        return this.restClient.downloadFile(sessionId, dataset.datasetId, file);
      })
      .subscribe();
  }

  getSessionByNameOrId(search: string) {
    return this.checkLogin()
      .mergeMap(() => this.restClient.getSessions())
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
      .mergeMap(() => this.getSessionId())
      .mergeMap(sessionId => this.restClient.getDatasets(sessionId))
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
      .mergeMap(s => this.restClient.deleteSession(s.sessionId))
      .subscribe();
  }

  sessionCreate(args) {
    this.checkLogin()
      .mergeMap(() => this.restClient.postSession({name: args.name}))
      .subscribe();
  }

  serviceList() {
    this.checkLogin()
      .mergeMap(() => this.restClient.getServices())
      .subscribe(
        services => console.log(services),
        err => console.error('failed to list services', err))
  }

  serviceGet(args) {
    this.checkLogin()
      .mergeMap(() => this.restClient.getServices())
      .map((services: Array<any>) => services.filter(s => !!s.publicUri && s.publicUri.startsWith('http')))
      .map((services: Array<any>) => services.filter(s => !args.name || s.serviceId === args.name || s.role === args.name))
      .mergeMap(services => Observable.from(services))
      .mergeMap(service => {
        return Observable.forkJoin(
          Observable.of(service),
          this.restClient.getStatus(service.publicUri)
            .catch(err => {
              console.log(service.role + ' error (' + service.publicUri + ')');
              return Observable.empty();
        }));
      })
      .toArray()
      .subscribe(statuses => {
          if (statuses.length === 1) {
            let res = statuses[0];
            let service = res[0];
            let status = res[1];
            for (let key in status) {
              let value = status[key];
              console.log(service.role + '\t' + service.serviceId+ '\t' + key + '\t' + value + '\t' + this.toHumanReadable(value));
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

  toHumanReadable(value) {
    if (Number.isInteger(value)) {
      if (value > Math.pow(1024, 3)) {
        value = Math.round(value / Math.pow(1024, 3) * 10) / 10 + ' G';
      } else if (value > Math.pow(1024, 2)) {
        value = Math.round(value / Math.pow(1024, 2) * 10) / 10 + ' M';
      } else if (value > 1024) {
        value = Math.round(value / 1024 * 10) / 10 + ' k'
      }
    }
    return value;
  }

  getPrompt(prompt, defaultValue = '', silent = false) {
    let subject = new Subject();

    read({ prompt: prompt, silent: silent, default: defaultValue}, function(err, line) {
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
