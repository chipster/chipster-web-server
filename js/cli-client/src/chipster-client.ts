import { RestClient } from "../../type-service/src/rest-client";
import { Observable } from "rxjs";
import ChipsterUtils from "./chipster-utils";
import { mergeMap } from "rxjs/operators";
//import { mergeMap } from "rxjs-compat/operator/mergeMap";

export default class ChipsterClient {

    constructor(private env, private restClient) {        
    }

    static login(webServerUri: string, username: string, password: string) {
        console.log('orig uri', webServerUri);    
        webServerUri = ChipsterUtils.fixUri(webServerUri);
        console.log('fixed uri', webServerUri);
        // get the service locator address
        return new RestClient(true, null, null).getServiceLocator(webServerUri).pipe(
            // get token
            mergeMap((serviceLocatorUrl: string) => {
                let guestClient = new RestClient(true, null, serviceLocatorUrl);
                return guestClient.getToken(username, password)
            }));
    }
}