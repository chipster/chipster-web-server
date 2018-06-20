import {Logger} from "../../type-service/src/logger";
import ChipsterClient from "./chipster-client";
import { RestClient } from "../../type-service/src/rest-client";
import chipsterClient from "./chipster-client";
import { Observable } from "rxjs";
import { tap } from "rxjs/operators";

const ArgumentParser = require('argparse').ArgumentParser;
const logger = Logger.getLogger(__filename);

export default class ReplaySession {

    chipsterClient: ChipsterClient;
    private tokenKey: string;

    constructor() {
        this.parseCommand();
      }
  
      parseCommand() {
  
      let parser = new ArgumentParser({
        version: '0.0.1',
        addHelp:true,
        description: 'Chipster session replay test',
      });
  
      parser.addArgument(['session'], { help: 'session file to replay' });
  
      let subparsers = parser.addSubparsers({
        title:'commands',
        dest:"command"
      });
    
    let loginSubparser = subparsers.addParser('login');
    
      loginSubparser.addArgument(['URL'], { nargs: '?', help: 'url of the API server'});
      loginSubparser.addArgument(['--username', '-u'], { help: 'username for the server'});
      loginSubparser.addArgument(['--password', '-p'], { help: 'password for the server'});
        
      let args = parser.parseArgs();
      
        this.replaySession(args);
    }
    
    replaySession(args) {

        ChipsterClient.login(args.URL, args.username, args.password).pipe(
            tap((token: any) => this.tokenKey = token.tokenKey))
        .subscribe(null, err => console.error(err));

        // const originalSessionId = chipsterClient.sessionUpload(args.sessions);
        // const replaySessionId = chipsterClient.sessionCreate();

        // const jobs = chipsterClient.getJobs();

        // jobs.forEach(job => this.replayJob(originalSessionId, replaySessionId, job));

        // chipsterClient.sessionDelete(replaySessionId);
        // chipsterClient.sessionDelete(originalSessionId);
    }

    // replayJob(originalSessionId, replaySessionId, job) {
    //     const datasetIdMap = new Map();
    //     job.inputs.forEach(input => {
    //         const localFileName = input.datasetId;
    //         this.chipsterClient.datasetDownload(originalSessionId, input.datasetId, localFileName);
    //         const newId = this.chipsterClient.datasetUpload(replaySessionId, localFileName);
    //         datasetIdMap.set(input.datasetId, newId);
    //     });
    //     //TDOO copy parameters and inputs
    //     this.chipsterClient.jobRun(tool, parameterMap, inputMap).getJobState$().subscribe(job => {
    //         logger.info('job state', job);            
    //     }    
    // }
}