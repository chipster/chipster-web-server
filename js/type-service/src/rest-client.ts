import {RxHR} from "@akanass/rx-http-request";
import {Logger} from "./logger";
import {Config} from "./config";
import { Observable, Subject } from "rxjs";
import 'rxjs/add/operator/map';
import 'rxjs/add/operator/mergeMap';
import 'rxjs/add/observable/forkJoin';
const restify = require('restify');
const request = require('request');
const fs = require('fs');
const errors = require('restify-errors');
const YAML = require('yamljs');

const logger = Logger.getLogger(__filename);

export class RestClient {

	private config;

  constructor(
    private isClient: boolean,
    public token: string,
    private serviceLocatorUri?: string) {

    if (!isClient) {
      this.config = new Config();
      this.serviceLocatorUri = this.config.get(Config.KEY_URL_INT_SERVICE_LOCATOR);
    }
  }

  getToken(username: string, password: string): Observable<any> {

    return this.getAuthUri()
      .map(authUri => authUri + '/tokens/')
      .mergeMap(uri => this.post(uri, this.getBasicAuthHeader(username, password)))
      .map(data => JSON.parse(data));
  }

  getStatus(host): Observable<any> {
    return this.getJson(host + '/admin/status', this.token);
  }

  getSessions(): Observable<any[]> {
    return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.getJson(sessionDbUri + '/sessions/', this.token));
  }

  getSession(sessionId: string): Observable<any> {
    return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.getJson(sessionDbUri + '/sessions/' + sessionId, this.token));
  }

  postSession(session: any) {
    return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.postJson(sessionDbUri + '/sessions/', this.token, session))
      .map((resp: any) => JSON.parse(resp).sessionId);
  }

  extractSession(sessionId: string, datasetId: string) {
    return this.getSessionWorkerUri()
      .mergeMap(uri => this.postJson(uri + '/sessions/' + sessionId + '/datasets/' + datasetId, this.token, null));
  }

  packageSession(sessionId: string, file: string) {
    return this.getSessionWorkerUri()
      .mergeMap(uri => this.getToFile(uri + '/sessions/' + sessionId, file));
  }

  deleteSession(sessionId: any) {
    return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.deleteWithToken(sessionDbUri + '/sessions/' + sessionId, this.token));
  }

	getDatasets(sessionId): Observable<any> {
		return this.getSessionDbUri().mergeMap(sessionDbUri => {
			return this.getJson(sessionDbUri + '/sessions/' + sessionId + '/datasets/', this.token);
		});
	}

	getDataset(sessionId, datasetId): Observable<any> {
		return this.getSessionDbUri().mergeMap(sessionDbUri => {
			return this.getJson(sessionDbUri + '/sessions/' + sessionId + '/datasets/' + datasetId, this.token);
		});
	}

	deleteDataset(sessionId: string, datasetId: string) {
	  return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.deleteWithToken(sessionDbUri + '/sessions/' + sessionId + '/datasets/' + datasetId, this.token));
  }

  postDataset(sessionId: string, dataset: any) {
    return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.postJson(sessionDbUri + '/sessions/' + sessionId + '/datasets/', this.token, dataset))
      .map((resp: any) => JSON.parse(resp).datasetId);
  }

  getJobs(sessionId): Observable<any> {
		return this.getSessionDbUri().mergeMap(sessionDbUri => {
			return this.getJson(sessionDbUri + '/sessions/' + sessionId + '/jobs/', this.token);
		});
	}

	getJob(sessionId, jobId): Observable<any> {
		return this.getSessionDbUri().mergeMap(sessionDbUri => {
			return this.getJson(sessionDbUri + '/sessions/' + sessionId + '/jobs/' + jobId, this.token);
		});
  }
  
  postJob(sessionId: string, job: any) {
    return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.postJson(sessionDbUri + '/sessions/' + sessionId + '/jobs/', this.token, job))
      .map((resp: any) => JSON.parse(resp).jobId);
  }

	deleteJob(sessionId: string, jobId: string) {
	  return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.deleteWithToken(sessionDbUri + '/sessions/' + sessionId + '/jobs/' + jobId, this.token));
  }

  getTools(): Observable<any> {
		return this.getToolboxUri().mergeMap(uri => {
			return this.getJson(uri + '/modules/', null);
		});
	}

	getTool(toolId): Observable<any> {
		return this.getToolboxUri().mergeMap(uri => {
			return this.getJson(uri + '/tools/' + toolId, null);
		});
	}

  downloadFile(sessionId: string, datasetId: string, file: string) {
    return this.getFileBrokerUri()
      .flatMap(fileBrokerUri => this.getToFile(fileBrokerUri + '/sessions/' + sessionId + '/datasets/' + datasetId, file));
  }

  getToFile(uri: string, file: string) {
    let subject = new Subject<any>();
    this.getFileBrokerUri()
      .subscribe(fileBrokerUri => {
        request.get(uri)
          .on('response', (resp) => this.checkForError(resp))
          .on('end', () => subject.next())
          .auth('token', this.token)
          .pipe(this.getWriteStream(file));
      });
    return subject;
  }

  getWriteStream(file: string) {
    if (file === '-') {
      return process.stdout;
    } else {
      return fs.createWriteStream(file);
    }
  }

  getReadStream(file: string) {
    if (file === '-') {
      return process.stdin;
    } else {
      return fs.createReadStream(file);
    }
  }

  uploadFile(sessionId: string, datasetId: string, file: string) {

    let subject = new Subject<any>();
    this.getFileBrokerUri()
      .subscribe(fileBrokerUri => {
        let req = request.put(fileBrokerUri + '/sessions/' + sessionId + '/datasets/' + datasetId)
          .auth('token', this.token)
          .on('response', (resp) => this.checkForError(resp))
          .on('end', () => subject.next());

        this.getReadStream(file)
          .pipe(req);
      });
    return subject;
  }

  getRules(sessionId): Observable<any> {
    return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.getJson(sessionDbUri + '/sessions/' + sessionId + '/rules', this.token));
  }

  postRule(sessionId: string, username: string, readWrite: boolean): Observable<any> {
    let rule = {session: {sessionId: sessionId}, username: username, readWrite: readWrite};
    return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.postJson(sessionDbUri + '/sessions/' + sessionId + '/rules', this.token, rule));
  }

  deleteRule(sessionId: string, ruleId: string) {
    return this.getSessionDbUri()
      .mergeMap(sessionDbUri => this.deleteWithToken(sessionDbUri + '/sessions/' + sessionId + '/rules/' + ruleId, this.token));
  }

  checkForError(response: any) {
    if (response.statusCode >= 300) {
      throw new Error(response.stausCode + ' - ' + response.statusMessage);
    }
  }

	getFile(sessionId, datasetId, maxLength) {
    // Range request 0-0 would produce 416 - Range Not Satifiable
	  if (maxLength === 0) {
	    return Observable.of("");
    }

		return this.getFileBrokerUri().mergeMap(fileBrokerUri => {
			return this.getWithToken(
				fileBrokerUri + '/sessions/' + sessionId + '/datasets/' + datasetId,
				this.token,
				{Range: 'bytes=0-' + maxLength});
		});
  }

	getAuthUri() {
		return this.getServiceUri('auth');
	}

	getFileBrokerUri() {
		return this.getServiceUri('file-broker');
	}

	getSessionDbUri() {
		return this.getServiceUri('session-db');
	}

  getSessionDbEventsUri() {
		return this.getServiceUri('session-db-events');
  }
  
  getToolboxUri() {
		return this.getServiceUri('toolbox');
  }
  
  getSessionWorkerUri() {
    return this.getServiceUri('session-worker');
  }

  getServices() {
    return this.getJson(this.serviceLocatorUri + '/services', null);
  }

	getServiceUri(serviceName) {
		return this.getServices().map(services => {
			let service = services.filter(service => service.role === serviceName)[0];
			if (!service) {
				Observable.throw(new errors.InternalServerError('service not found' + serviceName));
      }
      // the typeService doesn't have up-to-date token for itself, so we don't have access
      // to the internal URL
      //return this.isClient ? service.publicUri : service.uri;
      return service.publicUri;
		});
	}

	getServiceLocator(webServer) {
    return RxHR.get(webServer + '/assets/conf/chipster.yaml').map(resp => {
      let body = this.handleResponse(resp);
      let conf = YAML.parse(body);
      return conf['service-locator'];
    });
  }

  createJob(tool, paramMap, inputMap) {
    let job = {
    toolId: tool.name.id,
      state: 'NEW',
      parameters: [],
      inputs: [],
    };

    tool.parameters.forEach(p => {
      const param = {
        parameterId: p.name.id,
        displayName: p.name.displayName,
        description: p.name.description,
        type: p.type,
        value: p.defaultValue,
      };
      if (paramMap.has(p.name.id)) {
        param.value = paramMap.get(p.name.id);
      }
      job.parameters.push(param);
    });

    tool.inputs.forEach(i => {
      const input = {
        inputId: i.name.id,
        displayName: i.name.displayName,
        description: i.name.description,
        type: i.type.name,
        datasetId: null,
      };
      if (inputMap.has(i.name.id)) {
        input.datasetId = inputMap.get(i.name.id);
      } else if (i.isOptional !== true) {
        throw Error('non-optional input "' + i.name.id + '" has no dataset')
      }
      job.inputs.push(input);
    });
    return job;
  }

	getJson(uri: string, token: string): Observable<any> {
		return this.getWithToken(uri, token).map(data => JSON.parse(data));
	}

	getWithToken(uri: string, token: string, headers?: Object): Observable<string> {
	  if (token) {
      return this.get(uri, this.getBasicAuthHeader('token', token, headers));
    } else {
      return this.get(uri, headers);
    }
	}

  getBasicAuthHeader(username, password, headers?) {
    if (!headers) {
      headers = {};
    }

    headers['Authorization'] = 'Basic ' + new Buffer(username + ':' + password).toString('base64');

    return headers;
  }

	get(uri: string, headers?: Object): Observable<string> {
		let options = {headers: headers};

		logger.debug('get()', uri + ' ' + JSON.stringify(options.headers));

		return RxHR.get(uri, options).map(data => this.handleResponse(data));
	}

  post(uri: string, headers?: Object, body?: Object): Observable<string> {
    let options = {headers: headers, body: body};

    logger.debug('post()', uri + ' ' + JSON.stringify(options.headers));

    return RxHR.post(uri, options).map(data => this.handleResponse(data));
  }

  postJson(uri: string, token: string, data: any): Observable<string> {
    let headers = this.getBasicAuthHeader('token', token);
    headers['content-type'] = 'application/json';
    return this.post(uri, headers, JSON.stringify(data));
  }

  deleteWithToken(uri: string, token: string) {
    return this.delete(uri, this.getBasicAuthHeader('token', token));
  }

  delete(uri: string, headers?: Object): Observable<any> {
    let options = {headers: headers};

    return RxHR.delete(uri, options);
  }

	handleResponse(data) {
    if (data.response.statusCode >= 200 && data.response.statusCode <= 299) {
      logger.debug('response', data.body);
      return data.body;
    } else {
      if (data.response.statusCode >= 400 && data.response.statusCode <= 499) {
        logger.debug('error', data.response.statusCode + ' ' + data.response.statusMessage + ' ' + data.response.body);
        throw this.responseToError(data.response);
      } else {
        logger.error('error', data.response.statusCode + ' ' + data.response.statusMessage + ' ' + data.response.body);
        throw new errors.InternalServerError('request ' + data.response.request.method + ' ' + data.response.request.href + ' failed');
      }
    }
  }

	responseToError(response) {
	  if (this.isClient) {
	    return new Error(response.statusCode + ' - ' + response.statusMessage + ' (' + response.body + ') ' + response.request.href);
    } else {
      return new errors.HttpError({
        restCode: response.statusMessage,
        statusCode: response.statusCode,
        message: response.body
      });
    }
	}
}
