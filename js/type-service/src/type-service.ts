import {RestClient} from "./rest-client";
import {Observable} from "rxjs";
import {Logger} from "./logger";
import {Config} from "./config";
import {Tag, Tags, TypeTags} from "./type-tags";
const url = require('url');
const restify = require('restify');

const logger = Logger.getLogger(__filename);



class IdPair {
	constructor(
		public sessionId: string,
		public datasetId: string) {}
}

const MAX_CACHE_SIZE = 100 * 1000;
const MAX_HEADER_LENGTH = 4096;

export default class TypeService {

	private tagIdMap = new Map<string, Tag>();
	private cache = new Map<string, {}>();

	private config = new Config();

	constructor() {
		this.init();

		// the Tags object above is just for the code completion. For any real use
		// we wan't a real ES6 map
		for (let tagKey in Tags) {
			let tag = Tags[tagKey];
			this.tagIdMap.set(tag.id, tag);
		}
	}

	init() {
		var server = restify.createServer();
		server.use(restify.authorizationParser());
		server.use(restify.CORS({
			//origins: ['https://foo.com', 'http://bar.com', 'http://baz.com:8081'],   // defaults to ['*']
			//credentials: true,                 // defaults to false
			//headers: ['authorization']                 // sets expose-headers
		}));
		// CORS constructor above doesn't have way to add this
		restify.CORS.ALLOW_HEADERS.push('authorization');

		// add bodyParser to access the body, but disable the automatic parsing
		server.use(restify.bodyParser({ mapParams: false }));

		// reply to browser's pre-flight requests with CORS headers
		server.opts(/.*/, ( req, res ) => {
			// what is the purpose of the CORS plugin if it doesn't set this header?
			res.header( "Access-Control-Allow-Headers",     restify.CORS.ALLOW_HEADERS.join( ", " ) );
			res.send( 204 )
		} );

		server.get('/sessions/:sessionId', this.respond.bind(this));
		server.get('/sessions/:sessionId/datasets/:datasetId', this.respond.bind(this));

		let bindUrlString = this.config.get(Config.KEY_URL_BIND_TYPE_SERVICE);
		let bindUrl = url.parse(bindUrlString);

		server.listen(bindUrl.port, bindUrl.hostname, () => {
			logger.info('type-service listening at ' + bindUrlString);
		});
	}

	respond(req, res, next) {

	  let token;

	  try {
      token = this.getToken(req, next);
    } catch (e) {
      this.respondError(next, e);
      return;
    }

		let sessionId = req.params.sessionId;
		let datasetId = req.params.datasetId;

		logger.debug('type tag ' + sessionId + ' ' +  datasetId);

		if (!sessionId) {
			return next(new restify.BadRequest('sessionId missing'));
		}

		let datasets$;

		// check access permission by getting dataset objects
		if (datasetId) {
			// only one dataset requested
			datasets$ = new RestClient(false, token).getDataset(sessionId, datasetId).map(dataset => [dataset]);
		} else {
			// all datasets of the session requested
			datasets$ = new RestClient(false, token).getDatasets(sessionId);
		}

		let t0 = Date.now();

		datasets$.flatMap(datasets => {

			// array of observables that will resolve to [datasetId, typeTags] tuples
			let types$ = datasets.map(dataset => this.getTypeTags(sessionId, dataset, token));

			// wait for all observables to complete and return an array of tuples
			return types$.length ? Observable.forkJoin(types$) : Observable.of([]);

		}).subscribe(typesArray => {

			let types = this.tupleArrayToObject(typesArray);
			res.contentType = 'json';
			res.send(types);
			next();

			logger.info('response', JSON.stringify(types));
			logger.info('type tagging ' + typesArray.length + ' datasets took ' + (Date.now() - t0) + 'ms');
		}, err => {
		  this.respondError(next, err);
      // stop executing this method
      throw Error(err);
		});
	}

	respondError(next, err) {
    if (err.statusCode >= 400 && err.statusCode <= 499) {
      next(err);
    } else {
      logger.error('type tagging failed', err);
      next(new restify.InternalServerError('type tagging failed'));
    }
  }

	/**
	 * Takes an array of [key, value] tuples and converts it to a js object
	 *
	 * @param tuples
	 * @returns
	 */
	tupleArrayToObject(tuples) {
		let obj = {};
		for (let [key, value] of tuples) {
			obj[key] = value;
		}
		return obj;
	}

	getTypeTags(sessionId, dataset, token) {

		// always calculate fast type tags, because it's difficult to know when the name has changed
		let fastTags = TypeTags.getFastTypeTags(dataset.name);

		return this.getSlowTypeTagsCached(sessionId, dataset, token, fastTags)
      .map(slowTags => Object.assign({}, fastTags, slowTags))
			.map(allTags => [dataset.datasetId, allTags]);
	}

  /**
   * Slow tags depend on the fast tags, but we can't know if the fast tags have
   * changed and therefore can't update the slow tags. To update the slow tags, admin can restart
   * this service, or user has to export and import the file.
   *
   * @param sessionId
   * @param datasetId
   * @param token
   * @param fastTags
   * @returns {any}
   */
	getSlowTypeTagsCached(sessionId, dataset, token: string, fastTags) {
		let idPair = new IdPair(sessionId, dataset.datasetId);
		let cacheItem = this.getFromCache(idPair);

		if (cacheItem) {
			logger.debug('cache hit', sessionId + ' ' + dataset.datasetId);
			return Observable.of(cacheItem);

		} else {
			logger.info('cache miss', sessionId + ' ' + dataset.datasetId);
			return this.getSlowTypeTagsForDataset(sessionId, dataset, token, fastTags).map(slowTags => {
				this.addToCache(idPair, slowTags);
				return slowTags;
			});
		}
	}

	getFromCache(idPair) {
		if (this.cache.has(JSON.stringify(idPair))) {
			return this.cache.get(JSON.stringify(idPair));
		} else {
			return null;
		}
	}

	addToCache(idPair, tags) {
		// minus one to make space for the new entry
		while (this.cache.size > MAX_CACHE_SIZE - 1) {
			let oldestKey = this.cache.keys().next().value;
			this.cache.delete(oldestKey);
		}

		this.cache.set(JSON.stringify(idPair), tags);
	}

	getSlowTypeTagsForDataset(sessionId, dataset, token, fastTags) {
    let observable;
    if (Tags.TSV.id in fastTags) {
      observable = this.getHeaderNames(sessionId, dataset, token).map(headers => {
        return TypeTags.getSlowTypeTags(headers);
      });
    } else {
      observable = Observable.of({});
    }

    return observable;
  }

	getHeaderNames(sessionId, dataset, token) {
	  let requestSize = Math.min(MAX_HEADER_LENGTH, dataset.size);

		return new RestClient(false, token).getFile(sessionId, dataset.datasetId, requestSize).map(data => {
			return TypeTags.parseHeader(data);
		});
	}

	getToken(req: any, next: any) {
		if (req.authorization.scheme !== 'Basic') {
			throw new restify.UnauthorizedError('username must be token');
		}
		if (req.authorization.basic.username !== 'token') {
			throw new restify.UnauthorizedError('only token authentication supported');
		}

		return req.authorization.basic.password;
	}

}

if (require.main === module) {
	new TypeService();
}

