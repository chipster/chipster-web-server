import {RestClient} from "./rest-client";
import {Observable} from "rxjs";
import {Logger} from "./logger";
import {Config} from "./config";
const url = require('url');
const restify = require('restify');

const logger = Logger.getLogger(__filename);

export class Tag {
	constructor(
		public id: string,
		public extensions: string[]){}
}

// tags in an object for code completion
export const Tags = {
	// simple types are recognized with the file extension
	TEXT: new Tag('TEXT', ['.txt', '.dat', '.wee', '.seq', '.log', '.sam', '.fastq']),
	TSV: new Tag('TSV', ['.tsv']),
	CSV: new Tag('CSV', ['.csv']),
	PNG: new Tag('PNG', ['.png']),
	GIF: new Tag('GIF', ['.gif']),
	JPEG: new Tag('JPEG', ['.jpg', '.jpeg']),
	PDF: new Tag('PDF', ['.pdf']),
	HTML: new Tag('HTML', ['.html', '.htm']),
	TRE: new Tag('TRE', ['.tre']),
	AFFY: new Tag('AFFY', ['.cel']),
	BED: new Tag('BED', ['.bed']),
	GTF: new Tag('GTF', ['.gtf', '.gff', '.gff2', '.gff3']),
	FASTA: new Tag('FASTA', ['.fasta', '.fa', '.fna', '.fsa', '.mpfa']),
	FASTQ: new Tag('FASTQ', ['.fastq', '.fq']),
	GZIP: new Tag('GZIP', ['.gz']),
	VCF: new Tag('VCF', ['.vcf']),
	BAM: new Tag('BAM', ['.bam']),
	QUAL: new Tag('QUAL', ['.qual']),
	MOTHUR_OLIGOS: new Tag('MOTHUR_OLIGOS', ['.oligos']),
	MOTHUR_NAMES: new Tag('MOTHUR_NAMES', ['.names']),
	MOTHUR_GROUPS: new Tag('MOTHUR_GROUPS', ['.groups']),
	MOTHUR_STABILITY: new Tag('MOTHUR_STABILITY', ['.files']),
	MOTHUR_COUNT: new Tag('MOTHUR_COUNT', ['.count_table']),
	SFF: new Tag('SFF', ['.sff']),

	// complex types are defined here for autocompletion, but have to be checked separately
	GENELIST: new Tag('GENELIST', []),
	GENE_EXPRS: new Tag('GENE_EXPRS', []),
	CDNA: new Tag('CDNA', []),
	PHENODATA: new Tag('PHENODATA', []),
	GENERIC: new Tag('GENERIC', []),
  PVALUE_AND_FOLD_CHANGE: new Tag('PVALUE_AND_FOLD_CHANGE', []),
};

class IdPair {
	constructor(
		public sessionId: string,
		public datasetId: string) {}
}

const MAX_CACHE_SIZE = 100 * 1000;
const MAX_HEADER_LENGTH = 4096;

const PVALUE_HEADERS = ["p.", "pvalue", "padj", "PValue", "FDR"];
const FOLD_CHANGE_HEADERS = ["FC", "log2FoldChange", "logFC"];

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
			datasets$ = RestClient.getDataset(sessionId, datasetId, token).map(dataset => [dataset]);
		} else {
			// all datasets of the session requested
			datasets$ = RestClient.getDatasets(sessionId, token);
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
		let fastTags = this.getFastTypeTagsForDataset(dataset);

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

	getFastTypeTagsForDataset(dataset) {
		let typeTags = {};

		// add simple type tags based on file extensions
		for (let tagKey in Tags) { // for-in to iterate object keys
			for (let extension of Tags[tagKey].extensions) { // for-of to iterate array items
				if (dataset.name.endsWith(extension)) {
					typeTags[tagKey] = null;
				}
			}
		}
		return typeTags;
	}

	getSlowTypeTagsForDataset(sessionId, dataset, token, fastTags) {

		// copy the object, Object.assign() is from es6
		let slowTags = {};

		let observable;
		if (Tags.TSV.id in fastTags) {
			observable = this.getHeader(sessionId, dataset, token).map(headers => {
				//FIXME implement proper identifier column checks
				if (headers.indexOf('identifier') !== -1) {
					slowTags[Tags.GENELIST.id] = null;
				}

				if (headers.filter(header => header.startsWith('chip.')).length > 0) {
					slowTags[Tags.GENE_EXPRS.id] = null;
				}

				if (headers.indexOf('sample') !== -1) {
					slowTags[Tags.CDNA.id] = null;
				}

        if (TypeService.pValueAndFoldChangeCompatible(headers)) {
          slowTags[Tags.PVALUE_AND_FOLD_CHANGE.id] = null;
        }

        return slowTags;
			});
		} else {
			observable = Observable.of(slowTags);
		}

		return observable;
	}

	getHeader(sessionId, dataset, token) {

	  let requestSize = Math.min(MAX_HEADER_LENGTH, dataset.size);

		return RestClient.getFile(sessionId, dataset.datasetId, token, requestSize).map(data => {
			let firstRow = data.split('\n', 1)[0];
			let headers = firstRow.split('\t');
			return headers;
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

  static pValueAndFoldChangeCompatible(headers: string[]) {
	  return PVALUE_HEADERS.some(pValueHeader => headers.some(header => header.startsWith(pValueHeader))) &&
      FOLD_CHANGE_HEADERS.some(foldChangeHeader => headers.some(header => header.startsWith(foldChangeHeader)));
  }

}

if (require.main === module) {
	new TypeService();
}

