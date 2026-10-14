import {
  Dataset,
  Job,
  MetadataFile,
  PhenodataUtils,
  Tool,
  ToolInput,
} from "chipster-js-common";
import { RestClient } from "chipster-nodejs-core";
import { padStart } from "lodash-es";
import { Observable, Subject, forkJoin, from, of } from "rxjs";
import { defaultIfEmpty, map, mergeMap, tap } from "rxjs/operators";
import VError from "verror";

import path = require("path");
import { read } from "read";

export const missingInputError = "MissingInputError";

export default class ChipsterUtils {
  // the same as the limit of phenodata outputs in comp (OnDiskCompJobBase)
  static readonly PHENODATA_MAX_SIZE = 1024 * 1024;

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

    return from(
      read({
        prompt: prompt,
        silent: silent,
        default: defaultValue,
      }),
    );
  }

  static parseArgArray(array) {
    const map = new Map();
    if (array) {
      array.forEach((inputArg) => {
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
    restClient: RestClient,
  ) {
    return restClient.getServiceLocator(webServerUri).pipe(
      map((serviceLocatorUri) => {
        restClient.setServiceLocatorUri(serviceLocatorUri);
        restClient.setToken(token);
        return restClient;
      }),
    );
  }

  static getToken(
    webServerUri: string,
    username: string,
    password: string,
    restClient: RestClient,
  ) {
    // get the service locator address
    return restClient.getServiceLocator(webServerUri).pipe(
      // get token

      mergeMap((serviceLocatorUrl: any) => {
        restClient.setServiceLocatorUri(serviceLocatorUrl);
        return restClient.getToken(username, password);
      }),
    );
  }

  static sessionUpload(
    restClient: RestClient,
    file: string,
    name: string,
    printStatus: boolean,
  ): Observable<string> {
    const datasetName = path.basename(file);
    const sessionName = name || datasetName.replace(".zip", "");
    let sessionId;
    let datasetId;

    return restClient.postSession({ name: sessionName }).pipe(
      tap((id) => (sessionId = id)),
      tap((id) => {
        if (printStatus) {
          console.log("SessionID:", id);
        }
      }),
      mergeMap(() => restClient.postDataset(sessionId, { name: datasetName })),
      tap((id) => (datasetId = id)),
      tap(() => {
        if (printStatus) {
          console.log("Uploading");
        }
      }),
      mergeMap((datasetId) =>
        restClient.uploadFile(sessionId, datasetId, file),
      ),
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
      map(() => sessionId),
    );
  }

  static sessionCreate(restClient: RestClient, name: string) {
    return restClient.postSession({ name: name });
  }

  static datasetUpload(restClient, sessionId, file, name) {
    return restClient
      .postDataset(sessionId, { name: name })
      .pipe(
        mergeMap((datasetId) =>
          restClient.uploadFile(sessionId, datasetId, file),
        ),
      );
  }

  static jobRunWithMetadata(
    restClient: RestClient,
    sessionId: string,
    tool: Tool,
    paramMap: Map<string, string>,
    inputMap: Map<string, Dataset>,
    metadata: MetadataFile[],
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
      inputs: ChipsterUtils.getJobInputs(tool, inputMap),
      metadataFiles: metadata,
    };

    paramMap.forEach((value, parameterId) => {
      // comp will fill in display name etc.
      job.parameters.push({
        parameterId: parameterId,
        value: value,
      });
    });

    return restClient.postJob(sessionId, job);
  }

  /**
   * The data inputs of the job. Throws a missingInputError if a mandatory input has no dataset.
   */
  static getJobInputs(tool: Tool, inputMap: Map<string, Dataset>) {
    const inputs = [];
    tool.inputs.forEach((i) => {
      if (i.meta) {
        // phenodata is sent in metadataFiles, comp writes it to the job dir
        return;
      } else if (i.name.spliced) {
        // multi input
        for (let j = 1; j < 1000; j++) {
          const inputId = i.name.prefix + padStart(j, 3, "0") + i.name.postfix;
          if (inputMap.has(inputId)) {
            const input = ChipsterUtils.getInput(inputId, i, inputMap);
            inputs.push(input);
          } else {
            break;
          }
        }
      } else {
        // normal single input
        const input = ChipsterUtils.getInput(i.name.id, i, inputMap);
        // dont' add optional unbound inputs
        if (input != null) {
          inputs.push(input);
        }
      }
    });
    return inputs;
  }

  /**
   * Phenodata for the META inputs of the tool, to be sent in the job's metadataFiles.
   *
   * A META input given in inputMap gets the content of that dataset, e.g. an uploaded
   * phenodata file. The others get the phenodata of the input datasets or their
   * ancestors, like in the web app, but only when there is a single unbound META input
   * and the inputs lead to a single phenodata.
   */
  static getPhenodataFiles(
    restClient: RestClient,
    sessionId: string,
    tool: Tool,
    inputMap: Map<string, Dataset>,
  ): Observable<MetadataFile[]> {
    // report a missing data input first, the phenodata comes from the data inputs
    ChipsterUtils.getJobInputs(tool, inputMap);

    const metaInputs = tool.inputs.filter((i) => i.meta);
    const givenInputs = metaInputs.filter((i) => inputMap.has(i.name.id));
    const unboundInputs = metaInputs.filter((i) => !inputMap.has(i.name.id));

    const given$ = givenInputs.map((i) => {
      const dataset = inputMap.get(i.name.id);
      if (dataset.size == null) {
        throw new VError(
          'dataset "' + dataset.name + '" for ' + i.name.id + " has no content",
        );
      }
      if (dataset.size > ChipsterUtils.PHENODATA_MAX_SIZE) {
        throw new VError(
          'dataset "' +
            dataset.name +
            '" for ' +
            i.name.id +
            " is too large for phenodata (" +
            dataset.size +
            " bytes)",
        );
      }
      return restClient
        .getFile(sessionId, dataset.datasetId, dataset.size)
        .pipe(map((content: string) => ({ name: i.name.id, content })));
    });

    const unbound$ =
      unboundInputs.length === 0
        ? of([])
        : forkJoin({
            datasets: restClient.getDatasets(sessionId),
            jobs: restClient.getJobs(sessionId),
          }).pipe(
            map(({ datasets, jobs }) =>
              ChipsterUtils.bindPhenodata(
                unboundInputs,
                // the data inputs, not the META inputs given in inputMap
                Array.from(inputMap.entries())
                  .filter(
                    ([inputId]) =>
                      !metaInputs.some((i) => i.name.id === inputId),
                  )
                  .map(([, dataset]) => dataset),
                new Map((datasets as Dataset[]).map((d) => [d.datasetId, d])),
                new Map((jobs as Job[]).map((j) => [j.jobId, j])),
              ),
            ),
          );

    return forkJoin([forkJoin(given$).pipe(defaultIfEmpty([])), unbound$]).pipe(
      map(([given, unbound]) => [...given, ...unbound]),
    );
  }

  static bindPhenodata(
    unboundInputs: ToolInput[],
    dataInputs: Dataset[],
    datasetsMap: Map<string, Dataset>,
    jobsMap: Map<string, Job>,
  ): MetadataFile[] {
    const phenodataDatasets = dataInputs
      .map((dataset) =>
        PhenodataUtils.getPhenodataDataset(
          datasetsMap.get(dataset.datasetId) ?? dataset,
          jobsMap,
          datasetsMap,
          (d) => ChipsterUtils.phenodataTypeCheck(d),
        ),
      )
      .filter((d) => d != null);
    const uniqueIds = Array.from(
      new Set(phenodataDatasets.map((d) => d.datasetId)),
    );

    const mandatory = unboundInputs.filter((i) => !i.optional);
    if (unboundInputs.length === 1 && uniqueIds.length === 1) {
      const phenodata = PhenodataUtils.getOwnPhenodata(
        datasetsMap.get(uniqueIds[0]),
      );
      return [{ name: unboundInputs[0].name.id, content: phenodata }];
    }
    if (mandatory.length === 0) {
      return [];
    }
    let reason: string;
    if (unboundInputs.length > 1) {
      reason = "the tool has several phenodata inputs";
    } else if (uniqueIds.length === 0) {
      reason = "the input datasets have no phenodata";
    } else {
      reason = "the input datasets have different phenodata";
    }
    throw new VError(
      {
        name: missingInputError,
        info: { inputId: mandatory[0].name.id },
      },
      "can't choose the phenodata for " +
        mandatory.map((i) => i.name.id).join(", ") +
        ", because " +
        reason +
        ". Give a phenodata file for each with -i INPUT_ID=DATASET",
    );
  }

  static phenodataTypeCheck(dataset: Dataset) {
    return dataset.name.endsWith(".tsv") || dataset.name.endsWith(".TSV");
  }

  static getInput(
    inputId: string,
    toolInput: ToolInput,
    inputMap: Map<string, Dataset>,
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
            inputId: inputId,
          },
        },
        'non-optional input "' + inputId + '" has no dataset',
      );
    }
    return input;
  }

  // https://stackoverflow.com/a/2117523
  static uuidv4(): string {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(
      /[xy]/g,
      function (c) {
        const r = (Math.random() * 16) | 0,
          v = c == "x" ? r : (r & 0x3) | 0x8;
        return v.toString(16);
      },
    );
  }
}
