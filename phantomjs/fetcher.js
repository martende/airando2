var cli = require('./lib/cli');
var utils = require('./lib/utils');
var system = require('system');

var page = require('webpage').create();
var args = cli.parse(system.args);

if ( args.length < 4) console.log("ERROR: params");

args = {
	iataFrom: 'ORY',
	iataTo:   'BOJ', 
	traveltype: 'oneway', // ''
	departure:  '2014-07-01',
	//arrival:  	'2014-10-02',
	flclass: 		'economy',
	adults: 		1,
	childs: 		0,
	infants: 		0
};

args.module		 = system.args[1];
args.iataFrom  = system.args[2];
args.iataTo    = system.args[3];
args.departure = system.args[4];
args.flclass   = system.args[5];
args.adults    = system.args[6] || 1;
args.childs    = system.args[7] || 0;
args.infants   = system.args[8] || 0;

page.onConsoleMessage = function(msg) {
	if (msg == "$$INJECT") {
		//debug("injectClients");
		page.evaluate(function(_inj_query){
			eval("window._query = " + _inj_query + ";");
		},utils._query.toString());
		return;
	}
  console.log("CLIENT:" + msg);
};

page.viewportSize = {
  width: 600,
  height: 2400
};

module = require("./fetchers/"+ args.module).create({
	page:page,utils:utils
});

module.dosearch(args);
