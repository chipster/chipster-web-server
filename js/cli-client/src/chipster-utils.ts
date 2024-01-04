import { Dataset, Tool, ToolInput, ToolParameter } from "chipster-js-common";
import MetadataFile from "chipster-js-common/lib/model/metadata-file";
import { RestClient } from "chipster-nodejs-core";
import * as _ from "lodash";
import { Observable, Subject } from "rxjs";
import { map, mergeMap, tap } from "rxjs/operators";
import { VError } from "verror";

import path = require("path");
import read = require("read");

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
        if (nonNullValue.length >= widths[i]) {
          // add one more space to separate columns
          row += " ";
        }
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
    const subject = new Subject();

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

  static sessionUpload(
    restClient: RestClient,
    file: string,
    name: string,
    printStatus: boolean
  ): Observable<string> {
    const datasetName = path.basename(file);
    const sessionName = name || datasetName.replace(".zip", "");
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
        const warnings = JSON.parse(resp) as string[];
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

    const job = {
      toolId: tool.name.id,
      toolCategory: null, // not known in CLI
      toolName: tool.name.displayName,
      toolDescription: tool.description,
      state: "NEW",
      parameters: [],
      inputs: [],
      metadataFiles: metadata
    };

    paramMap.forEach((value, parameterId) => {
      // comp will fill in display name etc.
      job.parameters.push({
        parameterId: parameterId,
        value: value
      });
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
      displayName: null,
      description: toolInput.name.description,
      type: toolInput.type.name,
      datasetId: null,
      datasetName: null,
    };

    if (inputMap.has(inputId)) {
      input.datasetId = inputMap.get(inputId).datasetId;
      input.datasetName = inputMap.get(inputId).name;
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
      const r = (Math.random() * 16) | 0,
        v = c == "x" ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }
}
