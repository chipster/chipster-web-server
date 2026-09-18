import TypeService, { HttpError, MAX_CACHE_SIZE, Unauthorized } from "./type-service.js";
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { Observable, of as observableOf } from "rxjs";
import { Tags, TypeTags } from "./type-tags.js";

function basicAuthRequest(username: string, password: string) {
  const b64 = Buffer.from(username + ":" + password).toString("base64");
  return { headers: { authorization: "Basic " + b64 } };
}

/*
A response object that fails the test if it's used. The request handling must
stop when the token is missing, because responding here and continuing would
send a second response later.
*/
function forbiddenResponse() {
  return {
    status: () => assert.fail("res.status() must not be called"),
    send: () => assert.fail("res.send() must not be called"),
  };
}

describe("Test token parsing", () => {
  it("return the token of a valid header", () => {
    assert.equal(TypeService.getToken(basicAuthRequest("token", "abc123")), "abc123");
  });

  it("keep the colons of the password", () => {
    assert.equal(TypeService.getToken(basicAuthRequest("token", "abc:123:xyz")), "abc:123:xyz");
  });

  it("throw 401 for invalid headers", () => {
    const requests = {
      "missing header": { headers: {} },
      "empty header": { headers: { authorization: "" } },
      "no scheme": { headers: { authorization: "Basic" } },
      "wrong scheme": {
        headers: {
          authorization: "Bearer " + Buffer.from("token:abc123").toString("base64"),
        },
      },
      "no username and password separator": {
        headers: {
          authorization: "Basic " + Buffer.from("abc123").toString("base64"),
        },
      },
      "wrong username": basicAuthRequest("username", "abc123"),
    };

    for (const [description, req] of Object.entries(requests)) {
      assert.throws(
        () => TypeService.getToken(req),
        (err: HttpError) => {
          assert.ok(err instanceof Unauthorized, description);
          // respondError() forwards 4xx errors, anything else becomes a 500
          assert.equal(err.statusCode, 401, description);
          return true;
        },
        description,
      );
    }
  });
});

describe("Test error responses", () => {
  /*
  respondError() doesn't use "this", so we can call it without constructing
  TypeService, which would start the servers.
  */
  const respondError = TypeService.prototype.respondError;

  it("delegate 4xx errors to the error handler without responding", () => {
    const errors = [];

    respondError.call(
      null,
      forbiddenResponse(),
      (err) => errors.push(err),
      new Unauthorized("no authorization header"),
    );

    assert.equal(errors.length, 1);
    assert.equal(errors[0].statusCode, 401);
    assert.equal(errors[0].message, "no authorization header");
  });

  it("hide other errors behind a 500, but keep the original as the cause", () => {
    const errors = [];
    const original = new HttpError(503, "session-db is down");

    respondError.call(null, forbiddenResponse(), (err) => errors.push(err), original);

    assert.equal(errors.length, 1);
    assert.equal(errors[0].statusCode, 500);
    assert.equal(errors[0].message, "type tagging failed");
    // the client gets the generic message, but the log needs the real error
    assert.equal(errors[0].cause, original);
  });
});

/*
The cache methods use only "this.cache" and each other, so we can call them
without constructing TypeService, which would start the servers.
*/
function cacheOwner(slowTags: object = {}) {
  const requestedNames = [];

  return {
    cache: new Map(),
    requestedNames: requestedNames,
    getFromCache: TypeService.prototype.getFromCache,
    addToCache: TypeService.prototype.addToCache,
    getSlowTypeTagsForDataset: (sessionId, requestedDataset, _token) => {
      requestedNames.push(requestedDataset.name);
      return observableOf(slowTags);
    },
  };
}

function newDataset(name: string, fileId = "file1", size = 1000) {
  return { datasetId: "dataset1", name: name, fileId: fileId, size: size };
}

/* Get the value of a synchronous observable */
function getValue(observable: Observable<any>) {
  const values = [];
  observable.subscribe((value) => values.push(value));
  assert.equal(values.length, 1, "expected one value from the observable");
  return values[0];
}

function getSlowTypeTagsCached(owner, dataset) {
  return getValue(
    TypeService.prototype.getSlowTypeTagsCached.call(
      owner,
      "session1",
      dataset,
      "token1",
      TypeTags.getFastTypeTags(dataset.name),
    ),
  );
}

describe("Test slow type tag cache", () => {
  it("calculate the tags only once when the dataset hasn't changed", () => {
    const owner = cacheOwner({ [Tags.GENELIST.id]: null });

    assert.deepEqual(getSlowTypeTagsCached(owner, newDataset("results.tsv")), {
      [Tags.GENELIST.id]: null,
    });
    assert.deepEqual(getSlowTypeTagsCached(owner, newDataset("results.tsv")), {
      [Tags.GENELIST.id]: null,
    });

    assert.deepEqual(owner.requestedNames, ["results.tsv"]);
  });

  it("skip the cache when the dataset isn't a tsv file", () => {
    const owner = cacheOwner({ [Tags.GENELIST.id]: null });

    assert.deepEqual(getSlowTypeTagsCached(owner, newDataset("results.bam")), {});

    assert.deepEqual(owner.requestedNames, []);
    assert.equal(owner.cache.size, 0);
  });

  it("follow the name when the dataset is renamed", () => {
    const owner = cacheOwner({ [Tags.GENELIST.id]: null });

    getSlowTypeTagsCached(owner, newDataset("results.tsv"));
    // the new name isn't a tsv file anymore, so the cached tags must not be used
    assert.deepEqual(getSlowTypeTagsCached(owner, newDataset("results.bam")), {});
    // the file hasn't changed, so the old tags are still valid when renamed back
    assert.deepEqual(getSlowTypeTagsCached(owner, newDataset("results.tsv")), {
      [Tags.GENELIST.id]: null,
    });

    assert.deepEqual(owner.requestedNames, ["results.tsv"]);
  });

  it("calculate the tags again when the file is replaced", () => {
    const owner = cacheOwner({ [Tags.GENELIST.id]: null });

    getSlowTypeTagsCached(owner, newDataset("results.tsv", "file1"));
    getSlowTypeTagsCached(owner, newDataset("results.tsv", "file2"));
    getSlowTypeTagsCached(owner, newDataset("results.tsv", "file2", 2000));

    assert.deepEqual(owner.requestedNames, ["results.tsv", "results.tsv", "results.tsv"]);
    assert.equal(owner.cache.size, 1);
  });

  it("evict the least recently used entry", () => {
    const owner = cacheOwner();
    const key = (i: number) => TypeService.getCacheKey("session1", "dataset" + i);

    // fill the cache
    for (let i = 0; i < MAX_CACHE_SIZE; i++) {
      owner.addToCache(key(i), "signature", {});
    }

    // use the oldest entry to make the second oldest the least recently used
    assert.deepEqual(owner.getFromCache(key(0), "signature"), {});

    // the new entry doesn't fit in, one of the old ones has to go
    owner.addToCache(key(MAX_CACHE_SIZE), "signature", {});

    assert.equal(owner.cache.size, MAX_CACHE_SIZE);
    assert.notEqual(owner.getFromCache(key(0), "signature"), null);
    assert.equal(owner.getFromCache(key(1), "signature"), null);
  });

  it("forget an entry when the signature has changed", () => {
    const owner = cacheOwner();
    const key = TypeService.getCacheKey("session1", "dataset1");

    owner.addToCache(key, "signature1", {});

    assert.equal(owner.getFromCache(key, "signature2"), null);
    // the stale entry is removed, not just ignored
    assert.equal(owner.cache.size, 0);
  });
});
