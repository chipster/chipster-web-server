import { Subject } from "rxjs";
import { mergeMap, tap, map } from "rxjs/operators";
import { RestClient } from "chipster-js-common";
import { Observable } from "rxjs";
import * as _ from 'lodash';

const path = require('path');
const read = require('read');

export default class ChipsterUtils {
    
    static printStatus(args, status, value = null) {
        if (!args.quiet) {
            if (value != null) {
                console.log(status + ': \t' + value);
            } else {
                console.log(status);
            }
        } else if (value != null) {
            console.log(value);
        }
    }
    
    static printTable(obj, keys, widths) {
        let row = '';
        for (let i = 0; i < keys.length; i++) {
            const value = obj[keys[i]];
            if (widths.length > i) {
                const nonNullValue: any = '' + value;
                row += nonNullValue.padEnd(widths[i]);
            } else {
                row += value;
            }
        }
        console.log(row);
    }
    
    static toHumanReadable(value) {
        if (Number.isInteger(value)) {
            if (value > Math.pow(1024, 3)) {
                value = Math.round(value / Math.pow(1024, 3) * 10) / 10 + ' G';
            } else if (value > Math.pow(1024, 2)) {
                value = Math.round(value / Math.pow(1024, 2) * 10) / 10 + ' M';
            } else if (value > 1024) {
                value = Math.round(value / 1024 * 10) / 10 + ' k'
            }
        }
        return value;
    }
    
    static getPrompt(prompt, defaultValue = '', silent = false) {
        let subject = new Subject();
        
        read({ prompt: prompt, silent: silent, default: defaultValue}, function(err, line) {
            if (err) {
                subject.error(err);
            }
            subject.next(line);
            subject.complete();
        });
        
        return subject;
    }
    
    static parseArgArray(array) {
        const map = new Map();
        if (array) {
            array.forEach(inputArg => {
                const key = inputArg.slice(0, inputArg.indexOf('='));
                const value = inputArg.slice(inputArg.indexOf('=') + 1);
                map.set(key, value);
            });
        }
        return map;
    }
    
    static fixUri(uri) {
        if (!uri.startsWith('http')) {
            // add protocol
            uri = 'https://' + uri;
        }
        if (uri.endsWith('/')) {
            // remove trailing slash
            uri = uri.slice(0, -1);
        }
        return uri;
    }
    
    static getRestClient(webServerUri, token) {
        return new RestClient(true, null, null).getServiceLocator(webServerUri).pipe(
            map(serviceLocatorUri => new RestClient(true, token, serviceLocatorUri))
        );    
    }
    
    static login(webServerUri: string, username: string, password: string) {
        // get the service locator address
        return new RestClient(true, null, null).getServiceLocator(webServerUri).pipe(
            // get token
            mergeMap((serviceLocatorUrl: any) => {
                let guestClient = new RestClient(true, null, serviceLocatorUrl);
                return guestClient.getToken(username, password)
            })
        );
    }
    
    static sessionUpload(restClient: RestClient, file: string, name: string, printStatus: boolean): Observable<string> {
        
        let datasetName = path.basename(file);
        let sessionName = name || datasetName.replace('.zip', '');
        let sessionId;
        let datasetId;
        
        return restClient.postSession({ name: sessionName }).pipe(
            tap(id => sessionId = id),
            tap(id => {
                if (printStatus) {
                    console.log('SessionID:', id);
                }
            }),
            mergeMap(() => restClient.postDataset(sessionId, { name: datasetName })),
            tap(id => datasetId = id),
            tap(() => {
                if (printStatus) {
                    console.log('Uploading');
                }
            }),
            mergeMap(datasetId => restClient.uploadFile(sessionId, datasetId, file)),
            tap(() => {
                if (printStatus) {
                    console.log('Extracting');
                }
            }),
            mergeMap(() => restClient.extractSession(sessionId, datasetId)),
            tap((resp: string) => {
                const warnings = <string[]>JSON.parse(resp);
                if (warnings.length > 0) {
                    console.error('warnings', warnings);
                }
            }),
            mergeMap(() => restClient.deleteDataset(sessionId, datasetId)),
            map(() => sessionId),
        );    
    }
    
    static sessionCreate(restClient: RestClient, name: string) {
        return restClient.postSession({ name: name });
    }
    
    static datasetUpload(restClient, sessionId, file, name) {        
        return restClient.postDataset(sessionId, { name: name }).pipe(
            mergeMap(datasetId => restClient.uploadFile(sessionId, datasetId, file)),            
        );
    }
    
    static jobRun(restClient, sessionId, tool, paramMap, inputMap) {
        let job = {
            toolId: tool.name.id,
            state: 'NEW',
            parameters: [],
            inputs: [],
        };
        
        tool.parameters.forEach(p => {
            const param = {
                parameterId: p.name.id,
                displayName: p.name.displayName,
                description: p.name.description,
                type: p.type,
                value: p.defaultValue,
            };
            if (paramMap.has(p.name.id)) {
                param.value = paramMap.get(p.name.id);
            }
            job.parameters.push(param);
        });
        
        tool.inputs.forEach(i => {  
            if (i.name.id === 'phenodata.tsv' || i.name.id === 'phenodata2.tsv') {
                // phenodata will be generated in comp
                return;
            } else if (i.name.spliced) {
                // multi input
                for (let j = 1; j < 1000; j++) {
                    const inputId = i.name.prefix + _.padStart(j, 3, '0') + i.name.postfix;
                    if (inputMap.has(inputId)) {
                        const input = ChipsterUtils.getInput(inputId, i, inputMap);
                        job.inputs.push(input);        
                    } else {
                        break;
                    }
                }
            } else {
                // normal single input
                const input = ChipsterUtils.getInput(i.name.id, i, inputMap);
                // dont' add optional unbound inputs
                if (input != null) {                    
                    job.inputs.push(input);
                }
            }

        });

        return restClient.postJob(sessionId, job);
    }

    static getInput(inputId, toolInput, inputMap) {
        const input = {
            inputId: inputId,
            // apparently the app sends something here, because comp throws if this is null
            displayName: 'dataset-name-placeholder', 
            description: toolInput.name.description,
            type: toolInput.type.name,
            datasetId: null,
        };
        
        if (inputMap.has(inputId)) {
            input.datasetId = inputMap.get(inputId).datasetId;
            input.displayName = inputMap.get(inputId).name;
        } else if (toolInput.optional) {
            return null;
        } else {
            console.log('input', inputId, toolInput, inputMap.get(inputId));
            throw Error('non-optional input "' + inputId + '" has no dataset');
        }
        return input;
    }
}