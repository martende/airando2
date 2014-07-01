var cli = require('../lib/cli');
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

args.iataFrom  = system.args[1];
args.iataTo    = system.args[2];
args.departure = system.args[3];
args.flclass   = system.args[4];
args.adults    = system.args[5] || 1;
args.childs    = system.args[6] || 0;
args.infants   = system.args[7] || 0;

processArgs(args);

page.onConsoleMessage = function(msg) {
	if (msg == "$$INJECT") {
		//debug("injectClients");
		page.evaluate(function(_inj_query){
			eval("window._query = " + _inj_query + ";");
		},_query.toString());
		return;
	}
  console.log(msg);
};

page.viewportSize = {
  width: 600,
  height: 2400
};

page.open('http://www.norwegian.com/en/',parseIndex);


function parseIndex(success) {
	if ( success != 'success') {
		console.log("ERROR:status="+success);
		phantom.exit();return;
	}

	debug("processDestinations");
	if ( ! processDestinations() ) {
		phantom.exit();return;
	};


  debug("selectSrc");
  if ( ! selectSrc(args.iataFrom) ) {
  	phantom.exit();return;
  }
  debug("selectDst");
  if ( ! selectDst(args.iataTo) ) {
  	phantom.exit();return;
  }
  debug("selectTravelType");
  if ( ! selectTravelType(args.traveltype) ) {
  	phantom.exit();return;
  }
	debug("selectDates");
	if ( ! selectDates(args) ) {
  	phantom.exit();return;
  }
	debug("selectPassangers");
	if ( ! selectPassangers(args) ) {
  	phantom.exit();return;
  }

  //waitFor()
	click(".first .search a");

	waitFor(function() {
		return $$(".ErrorBox,.WarnBox,#avaday-outbound-result").exists() && 
		$$(".pagefooterbox").exists()
	},5000,testResult);

}

function testResult() {
	if ( $$(".ErrorBox").exists()) {
		if ( $$(".ErrorBox").contains("We could not find any flights on the selected dates") ) {
			console.log("PUSH:"+JSON.stringify({
				iataFrom: args.iataFrom,
				iataTo: 	args.iataTo,
				departure: args.departure,
				tickets: []
			}));
			done();
			return;
		} else {
			die("ErrorBox contains unknown error");
		}
		
	} if ( $$(".WarnBox").exists()) {
		if ( $$(".WarnBox").contains("We could not find any flights on the specified date, please use the Fare calendar to find an available date") ) {
			console.log("PUSH:"+JSON.stringify({
				iataFrom: args.iataFrom,
				iataTo: 	args.iataTo,
				departure: args.departure,
				tickets: []
			}));
			done();
			return;
		} else {
			die("ErrorBox contains unknown error");
		}
		
	} else if ( $$("#avaday-outbound-result").exists() ) {
		processResult();
		return;
	} else {
		die("Results page not recongnized");
	}

	
}

function processResult() {
	debug("processResult");
	$$("#avaday-outbound-result .avadaytable tr.rowinfo1 input[type=radio]").eachAsync(
		function($$el,i,cb) {
			var rowVars = $$el.evaluate(function(es) {
				var e = es[0];
				var td = e.parentNode.parentNode;
				var prctd = td.nextSibling;
				var tr = td.parentNode;
				var te = td;
				var ret = {};

				if (td.className.indexOf("standardflex")!= -1) {
					ret.fltype = 'flex';
				} else {
					ret.fltype = 'lowfare';
				}
				var dep = tr.querySelector(".depdest");
				ret.deptime = dep.innerText;
				var avl = tr.querySelector(".arrdest");
				ret.avltime = avl.innerText;
				ret.price = prctd.innerText;
				return ret;
			});
			if ( ! rowVars ) {
				die("processResult: "+i+"parsing table failed");
			}
			rowVars = soap(rowVars);
			if ( ! $$el.click() ) {
				die("ERROR: click on element: " + $$el.dump() + " failed");
			}

			setTimeout(function() {
				processOneTrack(i,20,rowVars,cb);
			},100);
		},
		function () {
			debug("processResultDone");
			done();
		}
	)
}

function processOneTrack(i,cnt,pattern,cb) {
	//console.log("processOneTrack " + i +"," + cnt + " for pattern" + JSON.stringify(pattern) );
	page.render('images/image'+i+'.png');
	var ret = page.evaluate(function() {
		var e = document.querySelectorAll("table.selectiontable")[0];
		var trs = e.querySelectorAll("tr");
		var lastI = null;
		for ( var i  = 2 ; i < trs.length ; i++) {
			if (trs[i].children.length == 2 ) {
				lastI = i;break;
			}
		}
		
		if ( lastI == null ) return null;
		var points = [];
		for ( var i = 2 ; i < lastI ; i+=4 ) {
			points.push({
				directions: trs[i].innerText,
				deptime: trs[i+1].innerText,
				depdate: trs[i+1].innerText,
				fltype: trs[i+2].innerText,
			})
		}
		price = trs[lastI].innerText;

		return {
			points: points,
			price:  price,
			fltype:  points[0].fltype,
			deptime: points[0].deptime,
			depdate: points[0].depdate,
		}
	});
	var partialret;

	if  (ret ) {
		ret.avltime = pattern.avltime;
		ret = soap(ret);
		partialret = ret;
		if ( ret.fltype != pattern.fltype || ret.deptime != pattern.deptime ) ret = null;
	}
	
	if ( ! ret ) {
		if ( cnt == 0 ) {
			die("ERROR:processOneTrack track " + i  + ". failed " + JSON.stringify({pattern:pattern , partialret:partialret}));
			// cb();
		} else {
			setTimeout(function() {
				processOneTrack(i,cnt-1,pattern,cb);
			},100);
		}
	} else {
		console.log("PUSH:",JSON.stringify({'tickets':ret}));	
		cb();
	}
}


function processArgs(a) {
	if (a.arrival) {
		var t = a.arrival.split("-");
		a.avl_dd = t[2];
		a.avl_yyyymm = t[0]+t[1];	
	}
	
	t = a.departure.split("-");
	a.dep_dd = t[2];
	a.dep_yyyymm = t[0]+t[1];

	if ( a.traveltype == "return" ) {
		console.log("ERROR:traveltype - return not supported");
  	phantom.exit();return;		
	}
	a.traveltype = a.traveltype == "oneway" ? 1 : 2;

}



function processDestinations() {
	var destinations = page.evaluate(function() {
  	var a = document.querySelectorAll("select.selectdestination");
  	var avl,dep;
  	var avls = [];
  	var deps = [];
  	for ( var i = 0; i < a.length ; i++) {
  		var id = a[i].id;
  		if (!dep && id.indexOf("Departure") != -1 ) {
  			dep = a[i];
  		} else if (!avl && id.indexOf("Arrival") != -1 ) {
  			avl = a[i];
  		}
  	}
  	var avlels = avl.querySelectorAll("option");
  	for ( var i = 0; i < avlels.length ; i++) {
  		var v = avlels[i].value;
  		if ( v.length==3)
  			avls.push(v);
  	}
  	var depels = dep.querySelectorAll("option");
  	for ( var i = 0; i < depels.length ; i++) {
  		var v = depels[i].value;
  		if ( v.length==3)
  			deps.push(v);
  	}
  	avls.sort();
  	deps.sort();
    return {avl:avls,dep:deps};
  });
	if (destinations && destinations.avl && destinations.dep) {
		console.log("PUSH:" + JSON.stringify(destinations));
		return true;
	} else {
		console.log("ERROR: processDestionations");
		return false;
	}

}
function selectDates(args) {
	return selectOption(["select[name$=DepartureDay]",0],args.dep_dd) && 
		selectOption(["select[name$=DepartureMonth]",0],args.dep_yyyymm)
//		selectOption(["select[name$=ReturnDay]",0],args.avl_dd) && 
//		selectOption(["select[name$=ReturnMonth]",0],args.avl_yyyymm)
	;
}
function selectPassangers(args) {
	return selectOption(["select[name$=AdultCount]",0],args.adults) && 
		selectOption(["select[name$=ChildCount]",0],args.childs) &&
		selectOption(["select[name$=InfantCount]",0],args.infants) 
	;
}
function selectTravelType(v) {
	return selectRadio("input[name$=TripType]",v);
}

function selectSrc(src) {
	return selectJSSelect(src, [ ".webpart.first .select2-container.selectdestination" , 0 ] ,
		".webpart.first .select2-dropdown-open ul.select2-results",
		[".select2-result","\\("+src+"\\)$"]
	)
}

function selectDst(dst) {
	return selectJSSelect(dst, [ ".webpart.first .select2-container.selectdestination" , 1 ] ,
		".webpart.first .select2-dropdown-open ul.select2-results",
		[".select2-result","\\("+dst+"\\)$"]
	)
}

// Util

function debug(msg) {
	console.log("DEBUG:"+msg);
}

function done() {
	page.render('images/example1.png');
	console.log("SUCCESS");
	phantom.exit();	
}





///

function die(err) {
	console.log("ERROR:"+err);
	page.render('images/error1.png');
	phantom.exit();
}

function waitFor(selector,timeout,cb) {
	if ( timeout < 100) timeout = 100;
	var ic = timeout / 100;
	if (typeof selector === "string" ) {
		selector = function() {
			return $$(selector).exists();
		}
	}
	var itvl = setInterval(function() {
		if (selector()) {
			clearInterval(itvl);
			cb();
		}
		ic--;
		if ( ic <= 0) {
			clearInterval(itvl);
			die("TIMEOUT");
		}
	},100);
}

function selectOption(selector,value) {
	return page.evaluate(function(selector,value) {
		var idx = null;
		var text2re = null;

		if (selector instanceof Array) {
			if ( selector[1].toFixed ) {
				idx = selector[1];
			} else {
				text2re   = RegExp(selector[1]);
			}
	  	selector = selector[0];    	
	  } else {
			text2re   = null;
	  }

		var ds = document.querySelectorAll(selector);
		var f = false;
		if ( ds.length == 0) {
			console.log("ERROR:selectOption:selector(" + selector + ") not found");
			return;
		} else if ( ds.length > 1 && idx == null ) {
			console.log("ERROR:selectOption:selector(" + selector + ") contains more than one element");
			return false;
		} else {
			idx = 0;
		}
		ds[idx].value = value;

		return true;
	},selector,value);
}

function selectRadio(selector,value) {
	return page.evaluate(function(selector,value) {
		var ds = document.querySelectorAll(selector);
		var f = false;
		if ( ds.length == 0) {
			console.log("ERROR:selectRadio:selector(" + selector + ") not found");
			return;
		}		
		for ( var i = 0;i< ds.length;i++) {
			if (ds[i].value == value) {
			 ds[i].checked="checked";
			 f = true;	
			}
			else ds[i].checked=null;
		}
		if ( ! f ) {
			console.log("ERROR: select radio failed");
			return false;
		}
		return true;
	},selector,value);
}

function selectJSSelect(value,button,container,element) {
	if ( ! click(button) ) {
		phantom.exit();return false;
  }

	var br = page.evaluate(function(selector,selector2) {
		var ds = document.querySelectorAll(selector);
		var text2re = null;
		if ( ds.length == 0) {
			console.log("ERROR:selectJSSelect:selector(" + selector + ") not found");
			return false;
		} else if ( ds.length > 1 ) {
			console.log("ERROR:selectJSSelect:selector(" + selector + ") contains more than one element");
			return false;
		}

    if (selector2 instanceof Array) {
    	text2re   = RegExp(selector2[1]);
    	selector2 = selector2[0];    	
    } else {
			text2re   = null;
    }
    
		var is = ds[0].querySelectorAll(selector2);
		var targetEl = null;
		if (is.length == 0) {
			console.log("ERROR:selectJSSelect:selector2(" + selector2 + ") not found");
			return false;
		}
		if ( text2re == null ) {
			targetEl = is[0];
		} else {
			for ( var i =0 ; i < is.length && ! targetEl ; i++) {
				var t = is[i].innerHTML;
				if (t.match(text2re)) {
					targetEl = is[i];
					break;
				}
			}	
		}
			
		if ( targetEl == null ) {
			console.log("ERROR:selectJSSelect:selector2(" + selector2 + ","+text2re+") not found");
			return false;
		}

		var o = targetEl.offsetParent;
		var ob = o.getBoundingClientRect();
		var tb = targetEl.getBoundingClientRect();
		if ( tb.top >= ob.top &&  tb.bottom <= ob.bottom ) {
			return tb;
		} else {
			var sof = (tb.bottom - ob.top - ob.height);
			console.log("DEBUG:set container scrollTop: " + sof);
			o.scrollTop = sof ;
			var tb = targetEl.getBoundingClientRect();
			if ( tb.top >= ob.top &&  tb.bottom <= ob.bottom ) {
				return tb;
			} else {
				console.log("ERROR: Moving Failed");
				return false;
			}
		}

		return false;

	},container,element);
	
	if ( br ) {
		page.sendEvent('click', br.left + br.width / 2, br.top + br.height / 2);		
		return true;
	}
	
	return false;
}
/////////////////////////////////////////////
// Util functions
/////////////////////////////////////////////


function getBoundingClientRect(selector) {
	return page.evaluate(function(selector) {
		var idx = null;
		var text2re = null;

		if (selector instanceof Array) {
			if ( selector[1].toFixed ) {
				idx = selector[1];
			} else {
				text2re   = RegExp(selector[1]);
			}
	  	selector = selector[0];    	
	  } else {
			text2re   = null;
	  }
		var ds = document.querySelectorAll(selector);
		if ( ds.length == 0) {
			return null;
		} else if ( ds.length > 1  ) {
			if ( idx == null ) {
				return null;	
			} else {
				return ds[idx].getBoundingClientRect();
			}
			
		}
		return ds[0].getBoundingClientRect();
	},selector);
}

function click(selector) {


	var rect = getBoundingClientRect(selector);
	if ( rect ) {
		var x = rect.left + rect.width / 2
		var y = rect.top + rect.height / 2;
		debug("click("+x+","+y+")");
		page.sendEvent('click', x, y);
		return true;	
	} else {
		return false;
	}	
}

function soap(ret) {
	var ret2 = {};
	for (var k in ret) {
		var v = ret[k];
		if ( v instanceof Array) {
			var tv = [];
			for (var i  = 0 ; i < v.length ; i++) {
				tv.push(soap(v[i]));
			}
			ret2[k]=tv;
			continue;
		} 
		else if (k=='deptime' || k == 'avltime') {
			var t = v.match(/\d\d:\d\d/);
			if (t && t.length > 0) v = t[0];
		} else if ( k=="price") {
			v = v.replace(",","").replace("\n","");
			var t = v.match(/\d+\.\d{1,2}/);
			if (t && t.length > 0) v = t[0];
		} else if ( k == "fltype") {
			var tv = v;
			v = v.toLowerCase();
			if ( v.indexOf("lowfare") != -1 ) v = "lowfare"; else v = "flex";
			var t = tv.match(/Flight\W(\w+)/);
			if (t && t.length > 0) {
				ret2['flnum'] = t[1];
			}

		} else if ( k == 'depdate') {
			var t = v.match(/(\w+) (\d{1,2})\. (\w+) (\d\d\d\d)\W(\d\d):(\d\d)/);
			if (t && t.length > 0) {
				var yyyy=t[4],MMs = t[3],dd=t[2],hh=t[5],mm=t[6];
				if ( dd.length == 1) dd = "0"+dd;
				v = yyyy + "-" + MMs + "-" + dd + " " + hh + ":" + mm;
			}
		} else if ( k == 'directions') {
			var t = v.match(/^(.*) - (.*)$/);
			if (t && t.length > 0) {
				ret2['dstName'] = t[1];
				ret2['avlName'] = t[2];
			}
		}
		ret2[k]=v;
	}
	return ret2;
}
function _query(selector) {
	var idx = null;
	var text2re = null;
	if (selector instanceof Array) {
		if ( selector[1].toFixed ) {
			idx = selector[1];
		} else {
			text2re   = RegExp(selector[1]);
		}
		selector = selector[0];    	
	} else {
		text2re   = null;
  }
  var ds = document.querySelectorAll(selector);
  if ( ds.length == 0) return [];
  if ( idx == null && text2re== null) {
  	return ds;
  } else {
  	if ( idx != null) {
  		if ( idx < ds.length )
  			return [ ds[idx] ] ;
  		else 
  			return [];
  	} else 
  		return ds;
  }
}

function $$(selector) {


	function exists() {
		return page.evaluate(function(selector) {
			if ( ! window._query ) console.log("$$INJECT");
			return _query(selector).length > 0;
		},selector);
	}

	function count() {
		return page.evaluate(function(selector) {
			if ( ! window._query ) console.log("$$INJECT");
			return _query(selector).length;
		},selector);
	}

	function html() {
		return page.evaluate(function(selector) {
			if ( ! window._query ) console.log("$$INJECT");
			var d = _query(selector);
			if ( d == null ) return null;
			else {
				if (d instanceof Array) {
					var r = "";
					for ( i = 0 ; i < d.length; i++) {
						r+=d[0].innerHTML;
					}	
					return r;
				} else return d.innerHTML;
			}
		},selector);
	}

	function dump() {
		return page.evaluate(function(selector) {
			if ( ! window._query ) console.log("$$INJECT");
			var d = _query(selector);
			var r = "";
			for ( i = 0 ; i < d.length; i++) {
				r+=d[0].outerHTML + "\n";
			}	
			return r;
		},selector);
	}

	function evaluate(f) {
		return page.evaluate(function(selector,f) {
			if ( ! window._query ) console.log("$$INJECT");
			var d = _query(selector);
			eval("var ex="+f);
			return ex(d);
		},selector,f);
	}

	function each(f) {
		var cnt = count();
		if ( cnt == 1 ) {
			f(this,0);
		} else if ( cnt != 0 ) {
			if (selector instanceof Array) {
				die("NONIMPLEMENTED");
			}

			for ( var i  = 0; i < cnt ; i++) {
				var ns = selector;
				if ( f($$([ns,i]),i) === false ) break;
			}	
		}		
	}

	function eachAsync(f,cb) {
		var cnt = count();

		if ( cnt == 1 ) {
			f(this,0,function(){});
			cb();
		} else if ( cnt != 0 ) {
			if (selector instanceof Array) {
				die("NONIMPLEMENTED");
			}
			var i = 0;
			var ieachCb = function() {
				if ( i >= cnt) cb();
				else {
					var ns = selector;
					i++;
					f($$([ns,i-1]),i-1,ieachCb);
				}
			}
			ieachCb();
		} else {
			cb();
		}
	}

	function contains(str) {
		return  dump().indexOf(str) != -1;		
	}

	function click() {
		var rect = page.evaluate(function(selector) {
			if ( ! window._query ) console.log("$$INJECT");
			var d = _query(selector);
			if ( d.length == 0 ) {
				console.log("ERROR:click:"+JSON.stringify(selector)+" element not found");
				return null;
			} else if ( d.length > 1 ) {
				console.log("ERROR:click:"+JSON.stringify(selector)+" many elements");
				return null;
			}
			return d[0].getBoundingClientRect();
		},selector);
		if ( rect ) {
			var x = rect.left + rect.width / 2
			var y = rect.top + rect.height / 2;
			debug("click("+x+","+y+")");
			page.sendEvent('click', x, y);
			return true;	
		} else {
			return false;
		}	
	}

	return {
		exists: exists,
		html: html,
		dump: dump,
		each: each,
		evaluate: evaluate,
		eachAsync: eachAsync,
		click:click,
		contains: contains
	}
}