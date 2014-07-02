var page ;
var utils;
var $$;
var args;

exports.create = function(_args) {
	page  = _args.page;
	utils = _args.utils;
  $$    = _args.utils.$$;
	self.dosearch = function(_args) {
    args = processArgs(_args);
    utils.debug("Process args:",args);
		page.open('http://www.norwegian.com/en/',parseIndex);
	}

	return self;
};


function parseIndex(success) {
  if ( success != 'success') 
    return utils.die("parseIndex status="+success);

  utils.debug("processDestinations");
  if ( ! processDestinations() ) 
    return utils.die("parseIndex:processDestinations");

  utils.debug("selectSrc");
  if ( ! selectSrc(args.iataFrom) ) 
    return utils.die("parseIndex:selectSrc");
    
  utils.debug("selectDst");
  if ( ! selectDst(args.iataTo) ) 
    return utils.die("parseIndex:selectDst");

  utils.debug("selectTravelType");
  if ( ! selectTravelType(args.traveltype) ) 
    return utils.die("parseIndex:selectTravelType");

  utils.debug("selectDates");
  if ( ! selectDates(args) ) 
    return utils.die("parseIndex:selectDates");

  utils.debug("selectPassangers");
  if ( ! selectPassangers(args) ) 
    return utils.die("parseIndex:selectPassangers");

  $$(".first .search a").click();

  utils.waitFor(function() {
    return $$(".ErrorBox,.WarnBox,#avaday-outbound-result").exists() && 
    $$(".pagefooterbox").exists()
  },5000,testResult);

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
  }
  return utils.die("ERROR: processDestionations");
}

function done() {
  console.log("SUCCESS");phantom.exit();
}
function testResult(ret) {
  if ( $$(".ErrorBox").exists()) {
    if ( $$(".ErrorBox").contains("We could not find any flights on the selected dates") ) {
      console.log("PUSH:"+JSON.stringify({
        iataFrom: args.iataFrom,
        iataTo:   args.iataTo,
        departure: args.departure,
        tickets: []
      }));
      done();
      return;
    } else {
      utils.die("ErrorBox contains unknown error");
    }
    
  } if ( $$(".WarnBox").exists()) {
    if ( $$(".WarnBox").contains("We could not find any flights on the specified date, please use the Fare calendar to find an available date") ) {
      console.log("PUSH:"+JSON.stringify({
        iataFrom: args.iataFrom,
        iataTo:   args.iataTo,
        departure: args.departure,
        tickets: []
      }));
      done();
      return;
    } else {
      utils.die("ErrorBox contains unknown error");
    }
    
  } else if ( $$("#avaday-outbound-result").exists() ) {
    processResult();
    return;
  } else {
    utils.die("Results page not recongnized");
  }

  
}



function processResult() {
  utils.debug("processResult");
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
        utils.die("processResult: "+i+"parsing table failed");
      }
      rowVars = utils.soap(rowVars);
      if ( ! $$el.click() ) {
        utils.die("ERROR: click on element: " + $$el.dump() + " failed");
      }

      setTimeout(function() {
        processOneTrack(i,20,rowVars,cb);
      },100);
    },
    function () {
      utils.debug("processResultDone");
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
    ret = utils.soap(ret);
    partialret = ret;
    if ( ret.fltype != pattern.fltype || ret.deptime != pattern.deptime ) ret = null;
  }
  
  if ( ! ret ) {
    if ( cnt == 0 ) {
      utils.die("ERROR:processOneTrack track " + i  + ". failed " + JSON.stringify({pattern:pattern , partialret:partialret}));
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



function selectDates(args) {
  return utils.selectOption(["select[name$=DepartureDay]",0],args.dep_dd) && 
    utils.selectOption(["select[name$=DepartureMonth]",0],args.dep_yyyymm)
//    selectOption(["select[name$=ReturnDay]",0],args.avl_dd) && 
//    selectOption(["select[name$=ReturnMonth]",0],args.avl_yyyymm)
  ;
}
function selectPassangers(args) {
  return utils.selectOption(["select[name$=AdultCount]",0],args.adults) && 
    utils.selectOption(["select[name$=ChildCount]",0],args.childs) &&
    utils.selectOption(["select[name$=InfantCount]",0],args.infants) 
  ;
}
function selectTravelType(v) {
  return utils.selectRadio("input[name$=TripType]",v);
}

function selectSrc(src) {
  return utils.selectJSSelect(src, $$([ ".webpart.first .select2-container.selectdestination" , 0 ]) ,
    ".webpart.first .select2-dropdown-open ul.select2-results",
    [".select2-result","\\("+src+"\\)$"]
  )
}

function selectDst(dst) {
  return utils.selectJSSelect(dst, $$([ ".webpart.first .select2-container.selectdestination" , 1 ]) ,
    ".webpart.first .select2-dropdown-open ul.select2-results",
    [".select2-result","\\("+dst+"\\)$"]
  )
}

function processArgs(_a) {
  var a = utils.clone(_a);
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
  return a;
}
