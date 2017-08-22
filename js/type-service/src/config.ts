import {Logger} from "./logger";
const fs = require('fs');
const YAML = require('yamljs');

const logger = Logger.getLogger(__filename);
const ROOT_PATH = '../../';
const DEFAULT_CONF_PATH = 'conf/chipster-defaults.yaml';
const KEY_CONF_PATH = 'conf-path';
const VARIABLE_PREFIX = 'variable-';

export class Config {

	public static readonly KEY_URL_BIND_TYPE_SERVICE = 'url-bind-type-service';
  public static readonly KEY_URL_ADMIN_BIND_TYPE_SERVICE = 'url-admin-bind-type-service';
	public static readonly KEY_URL_INT_SERVICE_LOCATOR = 'url-int-service-locator';

	private static confFileWarnShown = false;

	confPath: string;
	defaultConfPath: string;
	private variables = new Map<string, string>();

	constructor() {
		this.defaultConfPath = ROOT_PATH + DEFAULT_CONF_PATH;

		if (!fs.existsSync(this.defaultConfPath)) {
			throw new Error('default config file not found: ' + this.defaultConfPath);
		}


		let allDefaults = this.readFile(this.defaultConfPath);
		for (let key in allDefaults) {
			if (key.startsWith(VARIABLE_PREFIX)) {
				this.variables.set(key.replace(VARIABLE_PREFIX, ''), allDefaults[key]);
			}
		}

		this.confPath = ROOT_PATH + this.getDefault(KEY_CONF_PATH);
		if (!fs.existsSync(this.confPath)) {
			this.confPath = null;
			if (!Config.confFileWarnShown) {
				logger.warn('configuration file ' + this.confPath + ' not found, using defaults');
				Config.confFileWarnShown = true;
			} else {
				// swallow
			}
		}
	}

	get(key: string) {
		let value;
		if (this.confPath) {
			value = this.readFile(this.confPath)[key];
		}
		if (!value) {
			value = this.getDefault(key);
		}

		if (!value) {
			throw new Error('configuration key ' + key + ' not found');
		}
		return value;
	}

	getDefault(key: string) {
		let template = this.readFile(this.defaultConfPath)[key];

		this.variables.forEach((variableValue, variableKey) => {
			template = template.replace('\{\{' + variableKey + '\}\}', variableValue);
		});

		return template;
	}

	readFile(filePath: string) {
		return YAML.load(filePath);
	}
}
