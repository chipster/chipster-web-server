import {RxHR} from "@akanass/rx-http-request";
import {Observable} from "rxjs";
import {Logger} from "./logger";
import {Config} from "./config";
var restify = require('restify');

const logger = Logger.getLogger(__filename);

export class RestClient {

	static config = new Config();

	static getDataset(sessionId, datasetId, token): Observable<any> {
		return this.getSessionDbUri().mergeMap(sessionDbUri => {
			return RestClient.getJson(sessionDbUri + '/sessions/' + sessionId + '/datasets/' + datasetId, token);
		});
	}

	static getFile(sessionId, datasetId, token, maxLength) {

		return RestClient.getFileBrokerUri().mergeMap(fileBrokerUri => {
			return RestClient.get(
				fileBrokerUri + '/sessions/' + sessionId + '/datasets/' + datasetId,
				token,
				{Range: 'bytes=0-' + maxLength});
		});
	}

	static getAuthUri() {
		return RestClient.getServiceUri('authentication-service');
	}

	static getFileBrokerUri() {
		return RestClient.getServiceUri('file-broker');
	}

	static getSessionDbUri() {
		return RestClient.getServiceUri('session-db');
	}

	static getServiceUri(serviceName) {

		return RestClient.getJson(RestClient.config.get(Config.KEY_URL_INT_SERVICE_LOCATOR) + '/services', null).map(services => {
			let service = services.filter(service => service.role === serviceName)[0];
			if (!service) {
				Observable.throw(new restify.InternalServerError('service not found' + serviceName));
			}
			return service.uri;
		});
	}

	static getJson(uri: string, token: string): Observable<string> {
		return RestClient.get(uri, token).map(data => JSON.parse(data));
	}

	static get(uri: string, token: string, headers: Object): Observable<string> {
		logger.info('get()', uri + ' ' +  token);
		let options = {headers: {}};

		if (token) {
			options.headers = {
				Authorization: 'Basic ' + new Buffer('token:' + token).toString('base64')
			}
		}

		for (let header in headers) {
			options.headers[header] = headers[header];
		}

		return RxHR.get(uri, options).map(data => {
			if (data.response.statusCode >= 200 && data.response.statusCode <= 299) {
				logger.info('response', data.body);
				return data.body;
			} else {
				logger.error('error', data.response.statusCode + ' ' + data.response.statusMessage + ' ' + data.response.body);
				throw new restify.InternalServerError("unable to get the dataset from the session-db");
			}
		});
	}
}