import {RestClient} from "./rest-client";
import {Observable} from "rxjs";
import {Logger} from "./logger";
import {Config} from "./config";
const restify = require('restify');
const url = require('url');

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
};

class IdPair {
	constructor(
		public sessionId: string,
		public datasetId: string) {}
}

const MAX_CACHE_SIZE = 100 * 1000;
const MAX_HEADER_LENGTH = 4096;

class TypeService {

	private tagIdMap = new Map<string, Tag>();
	private cache = new Map<IdPair, string>();

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
			//headers: ['x-foo']                 // sets expose-headers
		}));
		server.use(restify.bodyParser({ mapParams: false }));

		server.post('/typetags', this.respond.bind(this));

		let bindUrlString = this.config.get(Config.KEY_URL_BIND_TYPE_SERVICE);
		let bindUrl = url.parse(bindUrlString);

		server.listen(bindUrl.port, bindUrl.hostname, () => {
			logger.info('type-service listening at ' + bindUrlString);
		});
	}

	respond(req, res, next) {

		let token = this.getToken(req, next);

		let idPairs = <IdPair[]>req.body;

		let observables = idPairs.map(idPair => this.getTypeTags(idPair, token));

		Observable.forkJoin(observables).subscribe(types => {
			res.contentType = 'json';
			res.send(types);
		}, err => {
			logger.error('type tagging failed', err);
			return next(restify.InternalServerError('type tagging failed'));
		});

		next();
	}

	getTypeTags(idPair: IdPair, token) {

		let cacheItem = this.getFromCache(idPair);

		if (cacheItem) {
			logger.info('cache hit', idPair.datasetId);
			return Observable.of({sessionId: idPair.sessionId, datasetId: idPair.datasetId, typeTags: cacheItem});

		} else {
			logger.info('cache miss', idPair.datasetId);
			return RestClient.getDataset(idPair.sessionId, idPair.datasetId, token).flatMap(dataset => {
				return this.getTypeTagsForDataset(idPair.sessionId, dataset, token).map(tags => {
					this.addToCache(idPair, tags);
					return Observable.of({sessionId: idPair.sessionId, datasetId: idPair.datasetId, typeTags: tags});
				});
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

	getTypeTagsForDataset(sessionId, dataset, token) {

		let typeTags = {};

		// add simple type tags based on file extensions
		for (let tagKey in Tags) { // for-in to iterate object keys
			for (let extension of Tags[tagKey].extensions) { // for-of to iterate array items
				if (dataset.name.endsWith(extension)) {
					typeTags[tagKey] = null;
				}
			}
		}

		let observable;
		if (Tags.TSV.id in typeTags) {
			observable = this.getHeader(sessionId, dataset.datasetId, token).map(headers => {
				//FIXME implement proper identifier column checks
				if (headers.indexOf('identifier') !== -1) {
					typeTags[Tags.GENELIST.id] = null;
				}

				if (headers.filter(header => header.startsWith('chip.')).length > 0) {
					typeTags[Tags.GENE_EXPRS.id] = null;
				}

				if (headers.indexOf('sample') !== -1) {
					typeTags[Tags.CDNA.id] = null;
				}

				return typeTags;
			});
		} else {
			observable = Observable.of(typeTags);
		}

		return observable;
	}

	getHeader(sessionId, datasetId, token) {

		return RestClient.getFile(sessionId, datasetId, token, MAX_HEADER_LENGTH).map(data => {
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
}

new TypeService();