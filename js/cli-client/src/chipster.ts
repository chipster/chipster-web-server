
import {RestClient} from "../../type-service/src/rest-client";
import {Observable, Subject} from "rxjs";
import {Logger} from "../../type-service/src/logger";
import CliEnvironment from "./cli-environment";

const read = require('read');
const readline = require('readline');
const ArgumentParser = require('argparse').ArgumentParser;
const url = require('url');
const logger = Logger.getLogger(__filename);

export default class CliClient {

	private restClient = null;
  private env = new CliEnvironment();

	constructor() {
	  this.parseCommand();
	}

	parseCommand() {

    var parser = new ArgumentParser({
      version: '0.0.1',
      addHelp:true,
      description: 'Chipster command line client for the version 4 and upwards',
    });

    var subparsers = parser.addSubparsers({
      title:'commands',
      dest:"command"
    });


    var sessionSubparsers = subparsers.addParser('session').addSubparsers({
      title:'session subcommands',
      dest:"subcommand"
    });

    var sessionListSubparser = sessionSubparsers.addParser('list');

    var sessionGetSubparser = sessionSubparsers.addParser('get');
    sessionGetSubparser.addArgument([ 'name' ], { help: 'session name or id' });

    var sessionDeleteSubparser = sessionSubparsers.addParser('delete');
    sessionDeleteSubparser.addArgument([ 'name' ], { help: 'session name or id' });

    var sessionCreateSubparser = sessionSubparsers.addParser('create');
    sessionCreateSubparser.addArgument([ 'name' ], { help: 'session name'});

    var sessionUploadSubparser = sessionSubparsers.addParser('upload');

    sessionUploadSubparser.addArgument([ 'file' ], { help: 'session file to upload' });
    sessionUploadSubparser.addArgument([ '--name' ], { help: 'session name' });

    var sessionDeleteSubparser = sessionSubparsers.addParser('delete');

    sessionDeleteSubparser.addArgument([ 'name' ], { help: 'foo3 bar3' });


    var datasetSubparsers = subparsers.addParser('dataset').addSubparsers({
      title:'dataset subcommands',
      dest:"subcommand"
    });

    var datasetListSubparser = datasetSubparsers.addParser('list');

    var loginSubparser = subparsers.addParser('login');

    loginSubparser.addArgument(['URL'], { nargs: '?', help: 'url of the API server'});
    loginSubparser.addArgument(['--username', '-u'], { help: 'username for the server'});
    loginSubparser.addArgument(['--password', '-p'], { help: 'password for the server'});

    subparsers.addParser('logout');

    var args = parser.parseArgs();

    logger.info(args);

    if (args.command === 'session') {
      if (args.subcommand === 'list') {
        this.sessionList();
      }
      if (args.subcommand === 'get') {
        this.sessionGet(args);
      }
      if (args.subcommand === 'create') {
        this.sessionCreate(args);
      }
      if (args.subcommand === 'delete') {
        this.sessionDelete(args);
      }
      if (args.subcommand === 'upload') {
        this.sessionUpload(args);
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
    this.checkLogin();
    console.log('Uploading ' + args.file + '...');
  }

  sessionList() {
    this.checkLogin()
      .flatMap(() => this.restClient.getSessions())
      .map((sessions: Array<any>) => sessions.map(s => s.name))
      .subscribe(sessions => console.log(sessions));
  }

  sessionGet(args) {
    this.getSessionByNameOrId(args.name, args.sessionId)
      .subscribe(sessions => console.log(sessions));
  }

  getSessionByNameOrId(name: string, id: string) {
    return this.checkLogin()
      .flatMap(() => this.restClient.getSessions())
      .map((sessions: Array<any>) => sessions.filter(s => s.name === name || s.sessionId === id))
      .map(sessions => {
        if (sessions.length !== 1) {
          throw new Error('found ' + sessions.length + ' sessions');
        }
        return sessions[0];
      });
  }

  sessionDelete(args) {
    this.getSessionByNameOrId(args.name, args.sessionId)
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
