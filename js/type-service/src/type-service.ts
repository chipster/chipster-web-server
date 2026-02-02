import { from, of as observableOf } from "rxjs";
import { map, mergeMap, tap } from "rxjs/operators";
import { Tag, Tags, TypeTags } from "./type-tags.js";
import { Logger } from "chipster-nodejs-core/lib/logger.js";
import { RestClient } from "chipster-nodejs-core/lib/rest-client.js";
import { Config } from "chipster-nodejs-core/lib/config.js";
import { fileURLToPath } from "url";

import express from "express";
import cors from "cors";
import os from "os";
import url from "url";

const logger = Logger.getLogger(fileURLToPath(import.meta.url));

class IdPair {
  constructor(
    public sessionId: string,
    public datasetId: string,
  ) {}
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

    if (
      process.env.NODE_ENV != null &&
      process.env.NODE_ENV.indexOf("production") > -1
    ) {
      logger.info("running in production mode");
    } else {
      logger.warn(
        "Express is running in development mode. Stack traces are visible in error responses.",
      );
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

          var apiServer = express();
          var adminServer = express();

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
    for (let tagKey in Tags) {
      let tag = Tags[tagKey];
      this.tagIdMap.set(tag.id, tag);
    }
  }

  initApiServer(server, corsOptions) {
    server.get("/sessions/:sessionId", cors(corsOptions), (req, res, next) => {
      this.respond(req, res, next);
    });
    server.get(
      "/sessions/:sessionId/datasets/:datasetId",
      cors(corsOptions),
      (req, res, next) => {
        this.respond(req, res, next);
      },
    );
    server.get("/admin/status", (req, res, next) => {
      (cors(corsOptions), this.respondStatus(req, res, next));
    });

    let bindUrlString = this.config.get(Config.KEY_URL_BIND_TYPE_SERVICE);
    let bindUrl = url.parse(bindUrlString);

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

    let bindUrlString = this.config.get(Config.KEY_URL_ADMIN_BIND_TYPE_SERVICE);
    let bindUrl = url.parse(bindUrlString);

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
        logger.error("http error: " + JSON.stringify(err) + " " + err.stack);
        if (err.statusCode != null) {
          res.status(err.statusCode);
        }
        if (err.message != null) {
          return res.send(err.message);
        } else {
          return res.send("unknown error");
        }
      }

      logger.error("non-http error: " + JSON.stringify(err) + err.stack);
      return res.status(500).send("unknown error");
    });
  }

  getCorsOptions() {
    logger.info("getCorsOptions()");
    // getting the allowed origin(s) from rest-client
    return this.serverRestClient.getServices().pipe(
      map((services: any[]) => {
        return services
          .filter((service) => service.role.startsWith("web-server"))
          .map((service) => service.publicUri);
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
      clientToken = this.getToken(req, res, next);
    } catch (e) {
      this.respondError(res, next, e);
      return;
    }

    let sessionId = req.params.sessionId;
    let datasetId = req.params.datasetId;

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
            this.getTypeTags(sessionId, dataset, clientToken),
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
            mergeMap((observable) => observable, maxConcurrent),
          );
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
              "ms",
          );
        },
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

  respondError(res, next, err) {
    if (err.statusCode >= 400 && err.statusCode <= 499) {
      // let client know about 4xx errors (e.g. session not found)
      // async error must be sent with next() for error handler to process it
      next(new HttpError(err.statusCode, err.message, err));
    } else {
      next(new InternalServerError("type tagging failed"));
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
        fastTags,
      ).pipe(
        map((slowTags) => Object.assign({}, fastTags, slowTags)),
        map((allTags) => [dataset.datasetId, allTags]),
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
        fastTags,
      ).pipe(
        map((slowTags) => {
          this.addToCache(idPair, slowTags);
          return slowTags;
        }),
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
    fastTags: Object,
  ) {
    let observable;
    if (Tags.TSV.id in fastTags) {
      observable = this.getParsedTsv(sessionId, dataset, token).pipe(
        map((table: any[][]) => {
          return TypeTags.getSlowTypeTags(table);
        }),
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
        }),
      );
  }

  /*
  Express does not include functionality for parsing HTTP Basic auth header and this is 
  not worth of adding a new dependency
  */
  getToken(req: any, res: any, next: any) {
    if (
      req.headers.authorization == null ||
      req.headers.authorization.length === 0
    ) {
      res.status(401).send("no authorization header");
      return;
    }

    const headerValue = req.headers.authorization.split(" ");

    if (headerValue.length != 2) {
      res.status(401).send("wrong header value length");
      return;
    }

    const [scheme, b64] = headerValue;

    if (scheme !== "Basic") {
      // throw new errors.UnauthorizedError("username must be token");
      res.status(401).send("username must be token");
      return;
    }

    const decoded = Buffer.from(b64, "base64").toString();

    const splitIndex = decoded.indexOf(":");

    if (splitIndex === -1) {
      res.status(401).send("cannot parse username and password");
      return;
    }

    const username = decoded.substring(0, splitIndex);
    // password can have ":"
    const password = decoded.substring(splitIndex + 1);

    if (username !== "token") {
      // throw new errors.UnauthorizedError("only token authentication supported");
      res.status(401).send("only token authentication supported");
      return;
    }

    return password;
  }
}

class HttpError extends Error {
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

class InternalServerError extends HttpError {
  constructor(message) {
    super(500, message);
  }
}

if (import.meta.url.endsWith(process.argv[1])) {
  new TypeService();
}
