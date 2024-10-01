import { from, of as observableOf } from "rxjs";
import { map, mergeMap } from "rxjs/operators";
import { Tag, Tags, TypeTags } from "./type-tags.js";
import { Logger } from "chipster-nodejs-core/lib/logger.js";
import { RestClient } from "chipster-nodejs-core/lib/rest-client.js";
import { Config } from "chipster-nodejs-core/lib/config.js";
import { fileURLToPath } from "url";

import os from "os";
import url from "url";
import restify from "restify";
import corsMiddleware from "restify-cors-middleware2";
import errors from "restify-errors";

const logger = Logger.getLogger(fileURLToPath(import.meta.url));

class IdPair {
  constructor(public sessionId: string, public datasetId: string) {}
}

const MAX_CACHE_SIZE = 100 * 1000;
const MAX_HEADER_LENGTH = 4096;

export default class TypeService {
  private tagIdMap = new Map<string, Tag>();
  private cache = new Map<string, {}>();

  private config = new Config();
  username: any;
  password: any;
  serverRestClient: any;

  constructor() {
    Logger.addLogFile();

    this.username = "type-service";
    this.password = this.config.get("service-password-type-service");

    this.serverRestClient = new RestClient(false, null, null);
    this.serverRestClient
      .getToken(this.username, this.password)
      .subscribe((serverToken) => {
        this.serverRestClient.setToken(serverToken);
        this.init();
        this.initAdmin();
      });

    // the Tags object above is just for the code completion. For any real use
    // we wan't a real ES6 map
    for (let tagKey in Tags) {
      let tag = Tags[tagKey];
      this.tagIdMap.set(tag.id, tag);
    }
  }

  init() {
    let server = this.createServer();

    server.get("/sessions/:sessionId", this.respond.bind(this));
    server.get(
      "/sessions/:sessionId/datasets/:datasetId",
      this.respond.bind(this)
    );
    server.get("/admin/status", this.respondStatus.bind(this));

    let bindUrlString = this.config.get(Config.KEY_URL_BIND_TYPE_SERVICE);
    let bindUrl = url.parse(bindUrlString);

    server.listen(bindUrl.port, bindUrl.hostname, () => {
      logger.info("type-service listening at " + bindUrlString);
    });
  }

  initAdmin() {
    let server = this.createServer();

    server.get("/admin/alive", this.respondAlive.bind(this));
    server.get("/admin/status", this.respondStatus.bind(this));

    let bindUrlString = this.config.get(Config.KEY_URL_ADMIN_BIND_TYPE_SERVICE);
    let bindUrl = url.parse(bindUrlString);

    server.listen(bindUrl.port, bindUrl.hostname, () => {
      logger.info("type-service listening at " + bindUrlString);
    });
  }

  createServer() {
    var server = restify.createServer();
    server.use(restify.plugins.authorizationParser());

    // getting the allowed origin from rest-client
    let originUri,
      originList = [],
      cors;

    this.serverRestClient
      //.getServiceUri("web-server") // get one web-server
      .getServices() // allow many web-servers (Chipster and Mylly) to use the same backend
      .pipe(
        map((services: any[]) => {
          return services
            .filter((service) => service.role.startsWith("web-server"))
            .map((service) => service.publicUri);
        })
      )
      .subscribe((webServers) => {
        cors = corsMiddleware({
          origins: webServers,
          allowHeaders: ["Authorization"],
          credentials: true,
        });
        server.pre(cors.preflight);
        server.use(cors.actual);
      });

    // add bodyParser to access the body, but disable the automatic parsing
    server.use(restify.plugins.bodyParser({ mapParams: false }));

    return server;
  }

  respond(req, res, next) {
    let clientToken;

    try {
      clientToken = this.getToken(req, next);
    } catch (e) {
      this.respondError(next, e);
      return;
    }

    let sessionId = req.params.sessionId;
    let datasetId = req.params.datasetId;

    logger.debug("type tag " + sessionId + " " + datasetId);

    if (!sessionId) {
      return next(new restify.BadRequest("sessionId missing"));
    }

    /* Configure RestClient to use internal addresses but client's token
     *
     * We have to use the client token to test the user's access rights.
     * But we have to use internal addresses to contact other services.
     *
     * Maybe we should impelement the token validation here and use server
     * token the check the access rights from the session-db.
     */
    let clientRestClient = new RestClient(false, clientToken, null);
    clientRestClient.services = this.serverRestClient.services;

    let datasets$;

    // check access permission by getting dataset objects
    if (datasetId) {
      // only one dataset requested
      datasets$ = clientRestClient
        .getDataset(sessionId, datasetId)
        .pipe(map((dataset) => [dataset]));
    } else {
      // all datasets of the session requested
      datasets$ = clientRestClient.getDatasets(sessionId);
    }

    let t0 = Date.now();

    // array of [datasetId, typeTags] tuples
    var allTypes = [];

    datasets$
      .pipe(
        mergeMap((datasets: any[]) => {
          // array of observables that will resolve to [datasetId, typeTags] tuples
          let types$ = datasets.map((dataset) =>
            this.getTypeTags(sessionId, dataset, clientToken)
          );

          // some results of a local test:
          // 1: type tagging 1072 datasets took 19312ms
          // 2: type tagging 1072 datasets took 9827ms
          // 4: type tagging 1072 datasets took 5931ms
          // 8: type tagging 1072 datasets took 4535ms
          // 16: type tagging 1072 datasets took 3500ms
          // 32: type tagging 1072 datasets took 3300ms
          // 64: type tagging 1072 datasets took 3853ms
          // 128: ECONNRESET
          const maxConcurrent = 16;

          return from(types$).pipe(
            mergeMap((observable) => observable, maxConcurrent)
          );
        })
      )
      .subscribe(
        // wait for all observables to complete and collect an array of tuples
        (oneResult) => {
          allTypes.push(oneResult);
        },
        (err) => {
          this.respondError(next, err);
        },
        () => {
          let types = this.tupleArrayToObject(allTypes);
          res.contentType = "json";
          res.send(types);
          next();

          // logger.info("response", JSON.stringify(types));
          logger.info(
            "type tagging " +
              allTypes.length +
              " datasets took " +
              (Date.now() - t0) +
              "ms"
          );
        }
      );
  }

  respondAlive(req, res, next) {
    res.send();
    next();
  }

  respondStatus(req, res, next) {
    //TODO this should be autenticated (but revealing the load value to localhost isn't yet a problem)
    res.contentType = "json";
    let status = {
      load: os.loadavg()[0], // 1 min load average
    };
    res.send(status);
    next();
  }

  respondError(next, err) {
    if (err.statusCode >= 400 && err.statusCode <= 499) {
      next(err);
    } else {
      logger.error("type tagging failed", err);
      next(new errors.InternalServerError("type tagging failed"));
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
    if (dataset.fileId != null) {
      // always calculate fast type tags, because it's difficult to know when the name has changed
      let fastTags = TypeTags.getFastTypeTags(dataset.name);

      return this.getSlowTypeTagsCached(
        sessionId,
        dataset,
        token,
        fastTags
      ).pipe(
        map((slowTags) => Object.assign({}, fastTags, slowTags)),
        map((allTags) => [dataset.datasetId, allTags])
      );
    } else {
      /* The dataset has been created, but the file hasn't been uploaded.
      No need to add type tags */
      return observableOf([dataset.datasetId, {}]);
    }
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
  getSlowTypeTagsCached(sessionId, dataset, token: string, fastTags: Object) {
    let idPair = new IdPair(sessionId, dataset.datasetId);
    let cacheItem = this.getFromCache(idPair);

    if (cacheItem) {
      logger.debug("cache hit", sessionId + " " + dataset.datasetId);
      return observableOf(cacheItem);
    } else {
      logger.info("cache miss", sessionId + " " + dataset.datasetId);
      return this.getSlowTypeTagsForDataset(
        sessionId,
        dataset,
        token,
        fastTags
      ).pipe(
        map((slowTags) => {
          this.addToCache(idPair, slowTags);
          return slowTags;
        })
      );
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

  getSlowTypeTagsForDataset(
    sessionId: string,
    dataset: string,
    token: string,
    fastTags: Object
  ) {
    let observable;
    if (Tags.TSV.id in fastTags) {
      observable = this.getParsedTsv(sessionId, dataset, token).pipe(
        map((table: any[][]) => {
          return TypeTags.getSlowTypeTags(table);
        })
      );
    } else {
      observable = observableOf({});
    }

    return observable;
  }

  getParsedTsv(sessionId, dataset, clientToken) {
    let requestSize = Math.min(MAX_HEADER_LENGTH, dataset.size);

    // Configure RestClient to use internal addresses but client's token
    let clientRestClient = new RestClient(false, clientToken, null);
    clientRestClient.services = this.serverRestClient.services;

    return clientRestClient
      .getFile(sessionId, dataset.datasetId, requestSize)
      .pipe(
        map((data: string) => {
          return TypeTags.parseTsv(data);
        })
      );
  }

  getToken(req: any, next: any) {
    if (req.authorization.scheme !== "Basic") {
      throw new errors.UnauthorizedError("username must be token");
    }
    if (req.authorization.basic.username !== "token") {
      throw new errors.UnauthorizedError("only token authentication supported");
    }

    return req.authorization.basic.password;
  }
}

if (import.meta.url.endsWith(process.argv[1])) {
  new TypeService();
}
