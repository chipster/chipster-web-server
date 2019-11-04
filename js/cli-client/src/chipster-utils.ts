import { Dataset, Tool, ToolInput } from "chipster-js-common";
import MetadataFile from "chipster-js-common/lib/model/metadata-file";
import { RestClient } from "chipster-nodejs-core";
import * as _ from "lodash";
import { Observable, Subject } from "rxjs";
import { map, mergeMap, tap } from "rxjs/operators";
import { VError } from "verror";

const path = require("path");
const read = require("read");

export const missingInputError = "MissingInputError";
export default class ChipsterUtils {
  static printStatus(args, status, value = null) {
    if (!args.quiet) {
      if (value != null) {
        console.log(status + ": \t" + value);
      } else {
        console.log(status);
      }
    } else if (value != null) {
      console.log(value);
    }
  }

  static printTable(obj, keys, widths) {
    let row = "";
    for (let i = 0; i < keys.length; i++) {
      const value = obj[keys[i]];
      if (widths.length > i) {
        const nonNullValue: any = "" + value;
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
        value = Math.round((value / Math.pow(1024, 3)) * 10) / 10 + " G";
      } else if (value > Math.pow(1024, 2)) {
        value = Math.round((value / Math.pow(1024, 2)) * 10) / 10 + " M";
      } else if (value > 1024) {
        value = Math.round((value / 1024) * 10) / 10 + " k";
      }
    }
    return value;
  }

  static getPrompt(prompt, defaultValue = "", silent = false) {
    let subject = new Subject();

    read({ prompt: prompt, silent: silent, default: defaultValue }, function(
      err,
      line
    ) {
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
        const key = inputArg.slice(0, inputArg.indexOf("="));
        const value = inputArg.slice(inputArg.indexOf("=") + 1);
        map.set(key, value);
      });
    }
    return map;
  }

  static fixUri(uri) {
    if (!uri.startsWith("http")) {
      // add protocol
      uri = "https://" + uri;
    }
    if (uri.endsWith("/")) {
      // remove trailing slash
      uri = uri.slice(0, -1);
    }
    return uri;
  }

  static configureRestClient(
    webServerUri: string,
    token: string,
    restClient: RestClient
  ) {
    return restClient.getServiceLocator(webServerUri).pipe(
      map(serviceLocatorUri => {
        restClient.setServiceLocatorUri(serviceLocatorUri);
        restClient.setToken(token);
        return restClient;
      })
    );
  }

  static getRestClient(webServerUri, token) {
    console.warn(
      "method ChipsterUtils.getRestClient() is deprecated, use configureRestClient() instead"
    );
    return this.configureRestClient(
      new RestClient(true, null, null),
      webServerUri,
      token
    );
  }

  static getToken(
    webServerUri: string,
    username: string,
    password: string,
    restClient: RestClient
  ) {
    // get the service locator address
    return restClient.getServiceLocator(webServerUri).pipe(
      // get token

      mergeMap((serviceLocatorUrl: any) => {
        restClient.setServiceLocatorUri(serviceLocatorUrl);
        return restClient.getToken(username, password);
      })
    );
  }

  static login(webServerUri: string, username: string, password: string) {
    console.warn(
      "method ChipsterUtils.login() is deprecated, use getToken() instead"
    );

    return this.getToken(
      webServerUri,
      username,
      password,
      new RestClient(true, null, null)
    );
  }

  static sessionUpload(
    restClient: RestClient,
    file: string,
    name: string,
    printStatus: boolean
  ): Observable<string> {
    let datasetName = path.basename(file);
    let sessionName = name || datasetName.replace(".zip", "");
    let sessionId;
    let datasetId;

    return restClient.postSession({ name: sessionName }).pipe(
      tap(id => (sessionId = id)),
      tap(id => {
        if (printStatus) {
          console.log("SessionID:", id);
        }
      }),
      mergeMap(() => restClient.postDataset(sessionId, { name: datasetName })),
      tap(id => (datasetId = id)),
      tap(() => {
        if (printStatus) {
          console.log("Uploading");
        }
      }),
      mergeMap(datasetId => restClient.uploadFile(sessionId, datasetId, file)),
      tap(() => {
        if (printStatus) {
          console.log("Extracting");
        }
      }),
      mergeMap(() => restClient.extractSession(sessionId, datasetId)),
      tap((resp: string) => {
        const warnings = <string[]>JSON.parse(resp);
        if (warnings.length > 0) {
          console.error("warnings", warnings);
        }
      }),
      mergeMap(() => restClient.deleteDataset(sessionId, datasetId)),
      map(() => sessionId)
    );
  }

  static sessionCreate(restClient: RestClient, name: string) {
    return restClient.postSession({ name: name });
  }

  static datasetUpload(restClient, sessionId, file, name) {
    return restClient
      .postDataset(sessionId, { name: name })
      .pipe(
        mergeMap(datasetId => restClient.uploadFile(sessionId, datasetId, file))
      );
  }

  static jobRun(
    restClient: RestClient,
    sessionId: string,
    tool: Tool,
    paramMap: Map<string, string>,
    inputMap: Map<string, Dataset>
  ) {
    return ChipsterUtils.jobRunWithMetadata(
      restClient,
      sessionId,
      tool,
      paramMap,
      inputMap,
      null
    );
  }

  static jobRunWithMetadata(
    restClient: RestClient,
    sessionId: string,
    tool: Tool,
    paramMap: Map<string, string>,
    inputMap: Map<string, Dataset>,
    metadata: MetadataFile[]
  ) {
    if (metadata == null) {
      metadata = [];
    }

    let job = {
      toolId: tool.name.id,
      state: "NEW",
      parameters: [],
      inputs: [],
      metadataFiles: metadata
    };

    tool.parameters.forEach(p => {
      const param = {
        parameterId: p.name.id,
        displayName: p.name.displayName,
        description: p.name.description,
        type: p.type,
        value: p.defaultValue
      };
      if (paramMap.has(p.name.id)) {
        param.value = paramMap.get(p.name.id);
      }
      job.parameters.push(param);
    });

    tool.inputs.forEach(i => {
      if (i.name.id === "phenodata.tsv" || i.name.id === "phenodata2.tsv") {
        // phenodata will be generated in comp
        return;
      } else if (i.name.spliced) {
        // multi input
        for (let j = 1; j < 1000; j++) {
          const inputId =
            i.name.prefix + _.padStart(j, 3, "0") + i.name.postfix;
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

  static getInput(
    inputId: string,
    toolInput: ToolInput,
    inputMap: Map<string, Dataset>
  ) {
    const input = {
      inputId: inputId,
      // apparently the app sends something here, because comp throws if this is null
      displayName: "dataset-name-placeholder",
      description: toolInput.name.description,
      type: toolInput.type.name,
      datasetId: null
    };

    if (inputMap.has(inputId)) {
      input.datasetId = inputMap.get(inputId).datasetId;
      input.displayName = inputMap.get(inputId).name;
    } else if (toolInput.optional) {
      return null;
    } else {
      throw new VError(
        {
          name: missingInputError,
          info: {
            inputId: inputId
          }
        },
        'non-optional input "' + inputId + '" has no dataset'
      );
    }
    return input;
  }

  // https://stackoverflow.com/a/2117523
  static uuidv4(): string {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function(c) {
      var r = (Math.random() * 16) | 0,
        v = c == "x" ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }
}
