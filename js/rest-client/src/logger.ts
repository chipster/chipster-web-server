const path = require('path');
const winston = require('winston');

export class Logger {

	static getLogger(filepath) {
		let filename = path.basename(filepath);
		return new (winston.Logger)({
			transports: [
				new (winston.transports.Console)({
					timestamp: function() {
						return new Date();
					},
					formatter: function(options) {

						let d = options.timestamp();
						var datestring = (
							d.getFullYear()
							+ "-" + ("0"+(d.getMonth()+1)).slice(-2))
							+ "-" + ("0" + d.getDate()).slice(-2)
							+ " " + ("0" + d.getHours()).slice(-2)
							+ ":" + ("0" + d.getMinutes()).slice(-2)
							+ ":" + ("0" + d.getSeconds()).slice(-2)
							+ "," + ("0" + d.getMilliseconds()).slice(-3);


						return '[' + datestring + '] '
							+ options.level.toUpperCase() + ': '
							+ (options.message ? options.message : '')
							+ (options.meta && Object.keys(options.meta).length ? '\n\t'+ JSON.stringify(options.meta) : '')
							+ ' (in ' + filename + ')';
					}
				})
			]
		});
	}
}
