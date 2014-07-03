var page ;
var utils;
var $$;
var args;

var tickets = [];
var name2iata = {};

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
    return utils.die("NOFLIGHTS");

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
    var name2iata={};
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
      
      if ( v.length==3) {
        var t = avlels[i].innerText;
        t = t.replace(/\W\(\w+\)$/,"").toLowerCase();
        name2iata[t] = v;
        avls.push(v);
      }
        
    }
    var depels = dep.querySelectorAll("option");
    for ( var i = 0; i < depels.length ; i++) {
      var v = depels[i].value;
      if ( v.length==3) {
        var t = depels[i].innerText;
        t = t.replace(/\W\(\w+\)$/,"").toLowerCase();
        name2iata[t] = v;
        deps.push(v);
      }
    }
    avls.sort();
    deps.sort();
    return {avl:avls,dep:deps,name2iata:name2iata};
  });

  if (destinations && destinations.avl && destinations.dep) {
    name2iata = destinations.name2iata;
    delete destinations.name2iata;
    console.log("PUSH:" + JSON.stringify(destinations));
    return true;
  }
  return utils.die("ERROR: processDestionations");
}

function done() {
  utils.debug("render to phantomjs/images/success.png");
  page.render("phantomjs/images/success.png");

  console.log("PUSH:"+JSON.stringify({
    results: {
      iataFrom:args.iataFrom,
      iataTo:args.iataTo,
      adults:args.adults,
      infants:args.infants || 0,
      children:args.children || 0,
      tickets: tickets
    }
  }));
  

  console.log("SUCCESS");phantom.exit();
}

function testResult(ret) {
  if ( $$(".ErrorBox").exists()) {
    if ( $$(".ErrorBox").contains("We could not find any flights on the selected dates") ) {
      /*console.log("PUSH:"+JSON.stringify({
        iataFrom: args.iataFrom,
        iataTo:   args.iataTo,
        departure: args.departure,
        tickets: []
      }));*/
      done();
      return;
    } else {
      utils.die("ErrorBox contains unknown error");
    }
    
  } if ( $$(".WarnBox").exists()) {
    if ( $$(".WarnBox").contains("We could not find any flights on the specified date, please use the Fare calendar to find an available date") ) {
      /*console.log("PUSH:"+JSON.stringify({
        iataFrom: args.iataFrom,
        iataTo:   args.iataTo,
        departure: args.departure,
        tickets: []
      }));*/
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

        
        var cn = td.className;

        if ( cn.indexOf("standardlowfare") != -1 ) {
          ret.flclass = 'lowfare';
        } else if (cn.indexOf("standardflex")!= -1 ) {
          ret.flclass = 'flex';
        } else if (cn.indexOf("premiumflex")!= -1 ) {
          ret.flclass = 'premiumflex';
        } else if (cn.indexOf("premiumlowfare")!= -1 ) {
          ret.flclass = 'premiumlowfare';
        } else {
          console.log("DEBUG: parsing price quality failed cn="+cn);
          return null;
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
      rowVars = soap(rowVars);
      utils.debug("CLICK to "+ i + " price, price: " + rowVars.price + " deptime: " + rowVars.deptime + " flclass=" + rowVars.flclass);
      page.evaluate(function() {
        var e = document.querySelectorAll("table.selectiontable")[0];
        e.setAttribute("dirty","1");
        e.style['background-color']='red';
      });
      if ( ! $$el.click() ) {
        utils.die("click on element: " + $$el.dump() + " failed");
      }

      setTimeout(function() {
        processOneTrack(i,30,rowVars,cb);
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
        flclass: trs[i+2].innerText,
      })
    }
    price = trs[lastI].innerText;

    return {
      points: points,
      price:  price,
      flclass:  points[0].flclass,
      deptime: points[0].deptime,
      depdate: points[0].depdate,
    }
  });
  var partialret;

  if  (ret ) {
    ret.avltime = pattern.avltime;
    ret = soap(ret);
    partialret = ret;
    if ( ret.flclass != pattern.flclass || ret.deptime != pattern.deptime ) ret = null;
  }
  
  if ( ! ret ) {
    if ( cnt == 0 ) {
      utils.die("processOneTrack track " + i  + ". failed " + JSON.stringify({pattern:pattern , partialret:partialret}));
      // cb();
    } else {
      setTimeout(function() {
        processOneTrack(i,cnt-1,pattern,cb);
      },100);
    }
  } else {
    tickets.push(ret);

    utils.debug("render to phantomjs/images/image"+i+'.png');
    page.render('phantomjs/images/image'+i+'.png');

    //console.log("PUSH:",JSON.stringify({'tickets':[ret]})); 
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
		utils.die("ERROR:traveltype - return not supported");
	}
	a.traveltype = a.traveltype == "oneway" ? 1 : 2;
  return a;
}

var monthsShort = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

function parseDate(v) {
  var t = v.match(/(\w+) (\d{1,2})\. (\w+) (\d\d\d\d)\W(\d\d):(\d\d)/);
  if (t && t.length > 0) {
      var yyyy=t[4],MMs = t[3],dd=t[2],hh=t[5],mm=t[6];
      var MM = monthsShort.indexOf(MMs);
      if ( MM == -1 ) utils.die("month not found");
      var d = new Date();
      d.setYear(yyyy);
      d.setMonth(MM);
      d.setDate(dd);
      d.setHours(hh);
      d.setMinutes(mm);
      d.setSeconds(0);
      return d;
      //v = yyyy + "-" + MMs + "-" + dd + " " + hh + ":" + mm;
  } else {
    utils.die("parseDate:" + v + " failed");
    return null;
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
        else if (k=='deptime') {
            var t = v.match(/\d\d:\d\d/);
            if (t && t.length > 0) v = t[0];
        } else if (k == 'avltime') {
          var t = v.match(/(\d\d:\d\d)( \+1)?/);
          if (t && t.length > 0) {
            if ( t.length == 3 ) {
              if ( ret['depdate'] ) {
                var date = parseDate(ret['depdate']);
                date.setDate(date.getDate() + 1);
                ret2['avldate'] = date.toISOString();
              }
            }
            
            v = t[1];
          }
        } else if ( k=="price") {
            v = v.replace(",","").replace("\n","");
            var t = v.match(/\d+\.\d{1,2}/);
            if (t && t.length > 0) v = t[0];
        } else if ( k == "flclass") {
            var tv = v;
            v = v.toLowerCase();
            if ( ! ( v == 'lowfare' || v == 'premiumlowfare' || v == 'flex' || v == 'premiumflex') ) {
              if ( v.indexOf("premiumlowfare") != -1 ) v = "premiumlowfare"; 
              else if ( v.indexOf("premiumflex") != -1 ) v = "premiumflex";
              else if ( v.indexOf("flex") != -1 ) v = "flex";
              else if ( v.indexOf("lowfare") != -1 ) v = "lowfare";
              else {
                v = "flex";
                console.log("WARN: flclass " + v + " not recongnized");
              }
            }
            
            var t = tv.match(/Flight\W(\w+)/);
            if (t && t.length > 0) {
                ret2['flnum'] = t[1].toUpperCase();
            }

        } else if ( k == 'depdate') {
          var date = parseDate(v);
          v = date.toISOString();
/*
            var t = v.match(/(\w+) (\d{1,2})\. (\w+) (\d\d\d\d)\W(\d\d):(\d\d)/);
            if (t && t.length > 0) {
                var yyyy=t[4],MMs = t[3],dd=t[2],hh=t[5],mm=t[6];
                if ( dd.length == 1) dd = "0"+dd;
                v = yyyy + "-" + MMs + "-" + dd + " " + hh + ":" + mm;
            }
            */
        } else if ( k == 'directions') {
          var els = v.toLowerCase().split(" - ");
          if (els.length == 2 ) {
            var dstName = els[0];
            var avlName = els[1];
            var iataFrom = calc_name2iata(dstName);
            var iataTo = calc_name2iata(avlName);
          } else {
            for ( i = 1 ; i < els.length -1 ; i++) {
              var dstName = els.slice(0,i).join(" - ");
              var avlName = els.slice(els.length-1-i).join(" - ");
              var iataFrom = calc_name2iata(dstName,true);
              var iataTo =  calc_name2iata(avlName,true);

              console.log("dstName:"+dstName+" iata:" +iataFrom);
              console.log("avlName:"+avlName+" iata:" +iataTo);

              if ( iataFrom && iataTo ) break;
            }
          }
          if ( iataFrom && iataTo ) {
            ret2['dstName'] = dstName;
            ret2['avlName'] = avlName;
            ret2['iataFrom'] = iataFrom;
            ret2['iataTo'] = iataTo;              
          } else {

            utils.die("cant split directions mask:" + v);
          }

        } else if ( k == 'dstName') {
          //ret2['iataFrom'] = calc_name2iata(v);
        } else if ( k == 'avlName') {
          //ret2['iataTo'] = calc_name2iata(v);
        }
        ret2[k]=v;
    }
    return ret2;
}

function calc_name2iata(v,silent) {
  v = v.toLowerCase();
  if ( name2iata[v]) {
    return name2iata[v];
  } else {
    if ( ! silent ) {
      console.log("WARN: dst_srcName: " + v + " not found");  
    }
    return '';
  }
}