import { Config, Logger, RestClient } from "chipster-nodejs-core";
import { UnauthorizedError } from "restify-errors";

const jwt = require('jsonwebtoken');


export class VerifyToken {

    constructor() {
    }

    verifyToken(token, config, jws_key) {
        let CLAIM_KEY_CLASS = "fi.csc.chipster.auth.resource.AuthTokens";

        let Roles = {
            admin: "password client admin",
            client: "password client"
        };
        let sign_algorithm = config.get(Config.KEY_JWS_ALGORITHM);

        let verifyOptions = {
            issuer: "chipster",
            audience: "chipster",
            algorithm: sign_algorithm
        }

        let legit = jwt.verify(token, jws_key, verifyOptions);

        if (legit.class !== CLAIM_KEY_CLASS) {
            return { ResCode: 401, Message: UnauthorizedError };
        }
        if (!(Object.values(Roles).includes(legit.roles))) {
            // invalid role
            return { ResCode: 401, Message: UnauthorizedError }
        } else if (Roles.client === legit.roles) {
            // no authentication require for now
            return { ResCode: 200, Message: "OK" }

        } else {
            jwt.verify(token, jws_key, verifyOptions), (err, decoded) => {
                if (err) {
                    return { ResCode: 403, Message: "NotAuthorizedError" }
                }
            }
            return { ResCode: 200, Message: "OK" };

        }
    }
}

