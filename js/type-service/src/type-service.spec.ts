import TypeService, { HttpError, Unauthorized } from "./type-service.js";
import { describe, it } from "node:test";
import assert from "node:assert/strict";

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
    assert.equal(
      TypeService.getToken(basicAuthRequest("token", "abc123")),
      "abc123",
    );
  });

  it("keep the colons of the password", () => {
    assert.equal(
      TypeService.getToken(basicAuthRequest("token", "abc:123:xyz")),
      "abc:123:xyz",
    );
  });

  it("throw 401 for invalid headers", () => {
    let requests = {
      "missing header": { headers: {} },
      "empty header": { headers: { authorization: "" } },
      "no scheme": { headers: { authorization: "Basic" } },
      "wrong scheme": {
        headers: {
          authorization:
            "Bearer " + Buffer.from("token:abc123").toString("base64"),
        },
      },
      "no username and password separator": {
        headers: {
          authorization: "Basic " + Buffer.from("abc123").toString("base64"),
        },
      },
      "wrong username": basicAuthRequest("username", "abc123"),
    };

    for (let [description, req] of Object.entries(requests)) {
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
  let respondError = TypeService.prototype.respondError;

  it("delegate 4xx errors to the error handler without responding", () => {
    let errors = [];

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

  it("hide other errors behind a 500", () => {
    let errors = [];

    respondError.call(
      null,
      forbiddenResponse(),
      (err) => errors.push(err),
      new HttpError(503, "session-db is down"),
    );

    assert.equal(errors.length, 1);
    assert.equal(errors[0].statusCode, 500);
    assert.equal(errors[0].message, "type tagging failed");
  });
});
