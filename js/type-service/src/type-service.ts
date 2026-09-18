import { from, Observable, of as observableOf } from "rxjs";
import { map, mergeMap, tap } from "rxjs/operators";
import { Tag, Tags, TypeTagMap, TypeTags } from "./type-tags.js";
import { Logger } from "chipster-nodejs-core/lib/logger.js";
import { RestClient } from "chipster-nodejs-core/lib/rest-client.js";
import { Config } from "chipster-nodejs-core/lib/config.js";
import { fileURLToPath } from "url";
import { startParentMonitor } from "./parent-monitor.js";

import express from "express";
import cors from "cors";
import os from "os";

const logger = Logger.getLogger(fileURLToPath(import.meta.url));

export const MAX_CACHE_SIZE = 100 * 1000;
const MAX_HEADER_LENGTH = 4096;

/* Cached slow tags and the inputs they were calculated from

The signature tells whether the cached tags are still valid, see
getCacheSignature().
*/
interface CacheItem {
  signature: string;
  tags: TypeTagMap;
}

export default class TypeService {
  private tagIdMap = new Map<string, Tag>();
  private cache = new Map<string, CacheItem>();

  private config = new Config();
  username: any;
  password: any;
  serverRestClient: any;

  constructor() {
    Logger.addLogFile();

    if (process.env.NODE_ENV != null && process.env.NODE_ENV.indexOf("production") > -1) {
      logger.info("running in production mode");
    } else {
      logger.warn("Express is running in development mode. Stack traces are visible in error responses.");
    }

    this.username = "type-service";
    this.password = this.config.get("service-password-type-service");

    this.serverRestClient = new RestClient(false, null, null);
    this.serverRestClient
      .getToken(this.username, this.password)
      .pipe(
        tap((serverToken) => {
          this.serverRestClient.setToken(serverToken);
        }),
        mergeMap(() => this.getCorsOptions()),
      )
      .subscribe(
        (corsOptions) => {
          logger.info("cors options", corsOptions);

          const apiServer = express();
          const adminServer = express();

          this.initApiServer(apiServer, corsOptions);
          this.initAdminServer(adminServer, corsOptions);

          // add cors headers to preflight requests
          apiServer.options("/{*any}", cors(corsOptions));
          adminServer.options("/{*any}", cors(corsOptions));

          this.addErrorHandler(apiServer);
          this.addErrorHandler(adminServer);
        },
        (err) => {
          logger.error("error in type-service", err);
        },
      );

    // the Tags object above is just for the code completion. For any real use
    // we want a real ES6 map
    for (const tagKey in Tags) {
      const tag = Tags[tagKey];
      this.tagIdMap.set(tag.id, tag);
    }
  }

  initApiServer(server, corsOptions) {
    server.get("/sessions/:sessionId", cors(corsOptions), (req, res, next) => {
      this.respond(req, res, next);
    });
    server.get("/sessions/:sessionId/datasets/:datasetId", cors(corsOptions), (req, res, next) => {
      this.respond(req, res, next);
    });
    server.get("/admin/status", cors(corsOptions), (req, res, next) => {
      this.respondStatus(req, res, next);
    });

    const bindUrlString = this.config.get(Config.KEY_URL_BIND_TYPE_SERVICE);
    const bindUrl = new URL(bindUrlString);

    server.listen(bindUrl.port, () => {
      logger.info("type-service listening at " + bindUrlString);
    });
  }

  initAdminServer(server, corsOptions) {
    server.get("/admin/alive", cors(corsOptions), (req, res, next) => {
      this.respondAlive(req, res, next);
    });

    server.get("/admin/status", cors(corsOptions), (req, res, next) => {
      this.respondStatus(req, res, next);
    });

    const bindUrlString = this.config.get(Config.KEY_URL_ADMIN_BIND_TYPE_SERVICE);
    const bindUrl = new URL(bindUrlString);

    server.listen(bindUrl.port, () => {
      logger.info("type-service listening at " + bindUrlString);
    });
  }

  addErrorHandler(server) {
    server.use((err, req, res, next) => {
      // express manual asks to delegate to Express error handler if headers have been sent already
      if (res.headersSent) {
        logger.warn("headers already sent, delegate to Express error handler");
        return next(err);
      }

      // Respond with message for expected errors (e.g. session not found).
      // By default, Express either responds with stack trace in development mode,
      // or no custom message at all in production mode.
      if (err instanceof HttpError) {
        if (err.statusCode >= 400 && err.statusCode <= 499) {
          // client errors are expected, log without a stack trace
          logger.warn("http error: " + err.statusCode + " " + err.message);
        } else {
          // the response hides these behind a generic message, so the original
          // error is only visible here
          logger.error("http error: " + err.statusCode + " " + err.stack);
          if (err.cause != null) {
            logger.error("caused by: " + (err.cause.stack ?? err.cause));
          }
        }
        if (err.statusCode != null) {
          res.status(err.statusCode);
        }
        if (err.message != null) {
          return res.send(err.message);
        }
        return res.send("unknown error");
      }

      logger.error("non-http error: " + JSON.stringify(err) + err.stack);
      return res.status(500).send("unknown error");
    });
  }

  getCorsOptions() {
    // getting the allowed origin(s) from rest-client
    return this.serverRestClient.getServices().pipe(
      map((services: any[]) => {
        return services.filter((service) => service.role.startsWith("web-server")).map((service) => service.publicUri);
      }),
      map((webServers) => {
        return {
          // the header would be always addded, if we would give a string (webServers[0])
          // now we give and array and header is added only when it matches with the Origin header in the request
          origin: webServers,
          allowedHeaders: ["Authorization"],
          credentials: true,
        };
      }),
    );
  }

  respond(req, res, next) {
    let clientToken;

    try {
      clientToken = TypeService.getToken(req);
    } catch (e) {
      this.respondError(res, next, e);
      return;
    }

    const sessionId = req.params.sessionId;
    const datasetId = req.params.datasetId;

    logger.debug("type tag " + sessionId + " " + datasetId);

    if (!sessionId) {
      // synchronous error we can simply throw
      throw new BadRequest("sessionId missing");
    }

    /* Configure RestClient to use internal addresses but client's token
     *
     * We have to use the client token to test the user's access rights.
     * But we have to use internal addresses to contact other services.
     *
     * Maybe we should impelement the token validation here and use server
     * token the check the access rights from the session-db.
     */
    const clientRestClient = new RestClient(false, clientToken, null);
    clientRestClient.services = this.serverRestClient.services;

    let datasets$;

    // check access permission by getting dataset objects
    if (datasetId) {
      // only one dataset requested
      datasets$ = clientRestClient.getDataset(sessionId, datasetId).pipe(map((dataset) => [dataset]));
    } else {
      // all datasets of the session requested
      datasets$ = clientRestClient.getDatasets(sessionId);
    }

    const t0 = Date.now();

    // array of [datasetId, typeTags] tuples
    const allTypes = [];

    datasets$
      .pipe(
        mergeMap((datasets: any[]) => {
          // array of observables that will resolve to [datasetId, typeTags] tuples
          const types$ = datasets.map((dataset) => this.getTypeTags(sessionId, dataset, clientToken));

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

          return from(types$).pipe(mergeMap((observable) => observable, maxConcurrent));
        }),
      )
      .subscribe(
        // wait for all observables to complete and collect an array of tuples
        (oneResult) => {
          allTypes.push(oneResult);
        },
        (err) => {
          this.respondError(res, next, err);
        },
        () => {
          const types = this.tupleArrayToObject(allTypes);
          res.send(types);

          // logger.info("response", JSON.stringify(types));
          logger.info("type tagging " + allTypes.length + " datasets took " + (Date.now() - t0) + "ms");
        },
      );
  }

  respondAlive(req, res, _next) {
    res.send();
  }

  respondStatus(req, res, _next) {
    //TODO this should be autenticated (but revealing the load value to localhost isn't yet a problem)
    const status = {
      load: os.loadavg()[0], // 1 min load average
    };
    res.send(status);
  }

  respondError(res, next, err) {
    if (err.statusCode >= 400 && err.statusCode <= 499) {
      // let client know about 4xx errors (e.g. session not found)
      // async error must be sent with next() for error handler to process it
      next(new HttpError(err.statusCode, err.message, err));
    } else {
      // don't leak internal details to the client, but keep the original
      // error for the log
      next(new InternalServerError("type tagging failed", err));
    }
  }

  /**
   * Takes an array of [key, value] tuples and converts it to a js object
   *
   * @param tuples
   * @returns
   */
  tupleArrayToObject(tuples) {
    const obj = {};
    for (const [key, value] of tuples) {
      obj[key] = value;
    }
    return obj;
  }

  getTypeTags(sessionId, dataset, token) {
    if (dataset.fileId != null) {
      // always calculate fast type tags, because it's difficult to know when the name has changed
      const fastTags = TypeTags.getFastTypeTags(dataset.name);

      return this.getSlowTypeTagsCached(sessionId, dataset, token, fastTags).pipe(
        map((slowTags) => Object.assign({}, fastTags, slowTags)),
        map((allTags) => [dataset.datasetId, allTags]),
      );
    }
    /* The dataset has been created, but the file hasn't been uploaded.
      No need to add type tags */
    return observableOf([dataset.datasetId, {}]);
  }

  /**
   * Slow tags are parsed from the file, but only for tsv files. The cache entry
   * is keyed by the session and dataset id, but it also stores a signature of
   * the file, so that a replaced file gets new slow tags instead of the stale
   * ones.
   *
   * @param sessionId
   * @param dataset
   * @param token
   * @param fastTags
   * @returns {any}
   */
  getSlowTypeTagsCached(sessionId, dataset, token: string, fastTags: TypeTagMap): Observable<TypeTagMap> {
    if (!(Tags.TSV.id in fastTags)) {
      // nothing to parse, don't waste cache entries on these
      return observableOf({});
    }

    const key = TypeService.getCacheKey(sessionId, dataset.datasetId);
    const signature = TypeService.getCacheSignature(dataset);
    const cachedTags = this.getFromCache(key, signature);

    if (cachedTags != null) {
      logger.debug("cache hit", sessionId + " " + dataset.datasetId);
      return observableOf(cachedTags);
    }
    logger.info("cache miss", sessionId + " " + dataset.datasetId);
    return this.getSlowTypeTagsForDataset(sessionId, dataset, token).pipe(
      map((slowTags) => {
        this.addToCache(key, signature, slowTags);
        return slowTags;
      }),
    );
  }

  static getCacheKey(sessionId: string, datasetId: string): string {
    return JSON.stringify([sessionId, datasetId]);
  }

  /**
   * Signature of the file the slow tags are parsed from
   *
   * The file id changes when the file is replaced and the size grows while an
   * upload is still in progress.
   *
   * @param dataset
   * @returns signature to compare against the cached one
   */
  static getCacheSignature(dataset): string {
    return JSON.stringify([dataset.fileId, dataset.size]);
  }

  getFromCache(key: string, signature: string): TypeTagMap | null {
    const cacheItem = this.cache.get(key);

    if (cacheItem == null) {
      return null;
    }

    if (cacheItem.signature !== signature) {
      // the dataset has changed, the tags have to be calculated again
      this.cache.delete(key);
      return null;
    }

    // move to the end of the insertion order to keep the eviction least
    // recently used
    this.cache.delete(key);
    this.cache.set(key, cacheItem);

    return cacheItem.tags;
  }

  addToCache(key: string, signature: string, tags: TypeTagMap) {
    // concurrent misses for the same dataset can both end up here, delete
    // first so that the entry doesn't keep the position of the earlier one
    this.cache.delete(key);

    while (this.cache.size >= MAX_CACHE_SIZE) {
      // the first key is the least recently used, because getFromCache() moves
      // the entries it returns to the end
      const lruKey = this.cache.keys().next().value;
      this.cache.delete(lruKey);
    }

    this.cache.set(key, { signature: signature, tags: tags });
  }

  getSlowTypeTagsForDataset(sessionId: string, dataset, token: string): Observable<TypeTagMap> {
    return this.getParsedTsv(sessionId, dataset, token).pipe(
      map((table) => {
        return TypeTags.getSlowTypeTags(table);
      }),
    );
  }

  /*
  RestClient has no type declarations, so the observable it returns is any, and
  the operators of a piped any get their value type from nowhere. The explicit
  return type here starts the typing of the chain again, so that the tags keep
  their type all the way into the cache.
  */
  getParsedTsv(sessionId, dataset, clientToken): Observable<string[][]> {
    const requestSize = Math.min(MAX_HEADER_LENGTH, dataset.size);

    // Configure RestClient to use internal addresses but client's token
    const clientRestClient = new RestClient(false, clientToken, null);
    clientRestClient.services = this.serverRestClient.services;

    return clientRestClient.getFile(sessionId, dataset.datasetId, requestSize).pipe(
      map((data: string) => {
        return TypeTags.parseTsv(data);
      }),
    );
  }

  /*
  Express does not include functionality for parsing HTTP Basic auth header and this is 
  not worth of adding a new dependency

  Throws Unauthorized, so that the caller stops the request handling. Sending
  the response here would let the handler continue with an undefined token.
  */
  static getToken(req: any): string {
    if (req.headers.authorization == null || req.headers.authorization.length === 0) {
      throw new Unauthorized("no authorization header");
    }

    const headerValue = req.headers.authorization.split(" ");

    if (headerValue.length !== 2) {
      throw new Unauthorized("wrong header value length");
    }

    const [scheme, b64] = headerValue;

    if (scheme !== "Basic") {
      throw new Unauthorized("username must be token");
    }

    const decoded = Buffer.from(b64, "base64").toString();

    const splitIndex = decoded.indexOf(":");

    if (splitIndex === -1) {
      throw new Unauthorized("cannot parse username and password");
    }

    const username = decoded.substring(0, splitIndex);
    // password can have ":"
    const password = decoded.substring(splitIndex + 1);

    if (username !== "token") {
      throw new Unauthorized("only token authentication supported");
    }

    return password;
  }
}

export class HttpError extends Error {
  public statusCode;
  public cause;
  constructor(statusCode, message, cause?) {
    super(message);
    this.statusCode = statusCode;
    this.cause = cause;
  }
}
class BadRequest extends HttpError {
  constructor(message) {
    super(400, message);
  }
}

export class Unauthorized extends HttpError {
  constructor(message) {
    super(401, message);
  }
}

class InternalServerError extends HttpError {
  constructor(message, cause?) {
    super(500, message, cause);
  }
}

if (import.meta.url.endsWith(process.argv[1])) {
  new TypeService();
  /* Started after the service, because its constructor configures the log file.
  The monitor exits the process if the parent process is killed, see
  startParentMonitor(). */
  startParentMonitor();
}
