import { Config, Logger, RestClient } from "chipster-nodejs-core";
import { Server } from 'restify';


const logger = Logger.getLogger(__filename);
const restify = require('restify');
const url = require('url');
const { Issuer } = require('openid-client');

//make this service stateless and no db lookup.

export default class OpenIDConnectService {

    private server: Server;

    constructor() {
        this.init();
    }

    init() {
        this.server = restify.createServer();
        logger.info("Server is created");
        this.server.get('/hello/:name', this.respond.bind(this));

        this.server.listen(8080, () =>
            logger.info('openid servicce listening at 8080')
        );



    }

    createServer() {

        const googleIssuer = new Issuer({
            issuer: 'https://accounts.google.com',
            authorization_endpoint: 'https://accounts.google.com/o/oauth2/v2/auth',
            userinfo_endpoint: 'https://www.googleapis.com/oauth2/v3/userinfo'

        });

        var client = new googleIssuer.Client({
            client_id: "",
            client_secret: ""
        });


        // for CSC AAI, we need to create new issuer 

        // Get the username and password
        // check if authentocated

        // post to CSC AAI, for oidc login

        // get the user profile, post req to authentication service for further processing 

    }

    respond(req, res, next) {
        res.send("hello" + req.params.name);
        next();
    }

}

if (require.main === module) {
    new OpenIDConnectService();
}