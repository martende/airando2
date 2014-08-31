define([
  'underscore',
  'backbone',
  'flightModel',
  'filterModel'
], function( _, Backbone,FlightModel,FilterModel){
  var flightsModel = Backbone.Collection.extend({
    model: FlightModel,

    initialize: function(models, ops) {
      this.idx = ops.idx;
      this.indexModel = ops.indexModel;
      this.filterModel = new FilterModel();
      this.gates = {};
      this.airlines = {};
      this.ticketByTuid = {};
      this.comparator = this.filterModel.getComparator();
      this.markDirty();
    },

    update: function() {
      var self = this;

      var cbIdx = Math.random().toString().substr(2);
      var iframe = $("<iframe/>").attr({
        "src":"/track/"+self.idx + "?cb=" + cbIdx,
        "style":"height:0px;width:0px"
      });
      var idx = self.idx;
      
      self.trigger("tick");

      window['cb'+cbIdx] = function(response) {
        if ( ! response || idx != self.idx) {
          delete window['cb'+cbIdx];
          iframe.remove();
          self.trigger("done");
          return;
        }

        if ( response.ok ) {
          console.log(response.data);
          self.trigger("tick");
          if ( response.data.airlines ) 
            self.updateAirlines(response.data.airlines);
          if ( response.data.gates ) 
            self.updateGatesInfo(response.data.gates);
          if ( response.data.currency_rates ) 
            self.indexModel.updateCurrenciesInfo(response.data.currency_rates);
          // should be last
          if ( response.data.tickets ) 
            self.updateTimetable(response.data.tickets);

        } else {
          self.trigger("error",response.error);
        }
      };

      $(document.body).append(iframe);

    },
    str2Date: function(strd) {
      r = strd.match(/^(\d\d\d\d)(\d\d)(\d\d)(\d\d)(\d\d)$/);
      if ( r ) return  new Date(r[1],r[2]-1,r[3],r[4],r[5]);
      throw "InvalidDate";
    },
    updateTimetable: function(data) {
      for ( var i = 0 ; i < data.length ; i++) {
        var df = data[i]['direct_flights'];
        if ( df ) {
          for ( var j = 0 ; j < df.length ; j++ ) {
            if ( df[j].arrival ) df[j].arrival = this.str2Date(df[j].arrival);
            if ( df[j].departure ) df[j].departure = this.str2Date(df[j].departure);
          }
        }
        df = data[i]['return_flights'];
        if ( df ) {
          for ( var j = 0 ; j < df.length ; j++ ) {
            if ( df[j].arrival ) df[j].arrival = this.str2Date(df[j].arrival);
            if ( df[j].departure ) df[j].departure = this.str2Date(df[j].departure);
          }
        }
      }
      /*
      var cmp = this.comparator;
      data.sort(function(x,y) {
        var vx = cmp(x);
        var vy = cmp(y);
        if (vx > vy) return 1 ;
        if (vx < vy) return -1 ;
        return 0;
      });
      */
      for ( var i = 0 ; i < data.length ; i++) {
        var om = this.ticketByTuid[data[i].tuid];
        if (  om ) {
          this.combineData(om,data[i]);
          //this.add(data[i]);  
        } else {
          var nm = this.add(data[i]);  
          this.ticketByTuid[data[i].tuid] = nm;
        }
        
      }
      this.markDirty();
      this.trigger("timetable");

    },
    combineData: function(om,d) {
      var onp = om.get('native_prices');
      var onu = om.get('order_urls');
      _.extend(onp,d.native_prices);
      _.extend(onu,d.order_urls);
      this.remove(om);
      om.reset();
      this.add(om);
      
    },
    updateAirlines: function(g) {
      this.markDirty();
      for ( var i = 0 ; i < g.length ; i++) {
        this.airlines[g[i].id]=g[i];
      }
    },

    updateGatesInfo: function(g) {
      this.markDirty();
      for ( var i = 0 ; i < g.length ; i++) {
        this.gates[g[i].id]=g[i];
      }
    },

    markDirty: function() {
      this._minPriceObj = null;
      this._maxPriceObj = null;
      this._minTimeObj  = null;
      this._maxTimeObj  = null;
      this._groupByStops = null;
      this._groupByAirlines = null;
      this._maxDurationObj = null;
      this._groupByDepAirports = null;
      this._groupByArvlAirports = null;
      this._groupByStopAirports = null;
      this._groupByAliances = null;
      this._groupByGates  = null;
    },

    getMinPriceObj: function() {
      if ( this._minPriceObj !== null ) return this._minPriceObj;
      var m = this.models;
      var ret = null;
      var reto = null;
      for ( var i = 0 ; i < m.length ; i++ ) {
        if ( ret == null || ret > m[i].getFullPrice() ) {
          ret  = m[i].getFullPrice();
          reto = m[i];
        }
      }
      this._minPriceObj = reto;
      return reto;
    },

    getMaxPriceObj: function() {
      if ( this._maxPriceObj !== null ) return this._maxPriceObj;
      var m = this.models;
      var ret = null;
      var reto = null;
      for ( var i = 0 ; i < m.length ; i++ ) {
        if ( ret == null || ret < m[i].getFullPrice() ) {
          ret  = m[i].getFullPrice();
          reto = m[i];
        }
      }
      this._maxPriceObj = reto;
      return reto;
    },

    getMinTimeObj: function() {
      if ( this._minTimeObj !== null ) return this._minTimeObj;
      var m = this.models;
      var ret = null;
      var reto = null;
      for ( var i = 0 ; i < m.length ; i++ ) {
        if ( ret == null || ret > m[i].getAvgDuration() ) {
          ret  = m[i].getAvgDuration();
          reto = m[i];
        }
      }
      this._minTimeObj = reto;
      return reto;
    },

    getMaxTimeObj: function() {
      if ( this._maxTimeObj !== null ) return this._maxTimeObj;
      var m = this.models;
      var ret = null;
      var reto = null;
      for ( var i = 0 ; i < m.length ; i++ ) {
        if ( ret == null || ret < m[i].getAvgDuration() ) {
          ret  = m[i].getAvgDuration();
          reto = m[i];
        }
      }
      this._maxTimeObj = reto;
      return reto;
    },

    getMaxPauseObj: function() {
      if ( this._maxDurationObj !== null ) return this._maxDurationObj;
      var m = this.models;
      var ret = null;
      var reto = null;
      for ( var i = 0 ; i < m.length ; i++ ) {
        if ( ret == null || ret < m[i].getTotalPause() ) {
          ret  = m[i].getTotalPause();
          reto = m[i];
        }
      }
      this._maxDurationObj = reto;
      return reto;

    },

    getGroupByStops: function() {
      if ( this._groupByStops !== null ) return this._groupByStops;
      var m = this.models;
      var gps = {};
      for ( var i = 0 ; i < m.length ; i++ ) {
        var stopsCnt = m[i].getStopsCnt();
        if ( stopsCnt > 1 ) {
          stopsCnt = 2;
        }
        for (var j = stopsCnt ; j <= 2 ; j++) {
          var minPrice = m[i].getFullPrice();
          if ( gps[j] === undefined ) {
            gps[j] = {
              cnt:0,
              minPrice:null
            };
          }
          if (gps[j]['minPrice'] === null || 
            gps[j].minPrice > minPrice
          ) gps[j]['minPrice'] = minPrice;
          gps[j]['cnt']++;          
        }
      }      

      this._groupByStops = gps;
      return gps;
    },
    groupBy: function(key,pred,propagate) {
      if ( this[key] !== null ) return this[key];
      if ( ! propagate ) propagate = [];
      var gps = {};
      var m = this.models;
      for ( var i = 0 ; i < m.length ; i++ ) {
        var minPrice = m[i].getFullPrice();
        var avsa = m[i][pred]();
        if (! _.isArray(avsa) ) avsa = [avsa];
        for ( var j = 0 ; j < avsa.length ; j++ ) {
          var aid = avsa[j].id;
          if ( gps[aid] === undefined ) {
            gps[aid] = {
              cnt:0,
              minPrice:null,
              id: aid,
              name: avsa[j].name || aid
            };
            for ( var k = 0 ; k <propagate.length;k++ ) {
              gps[aid][propagate[k]]=avsa[j][propagate[k]];
            }
          }
          if (gps[aid]['minPrice'] === null || 
            gps[aid].minPrice > minPrice
          ) gps[aid]['minPrice'] = minPrice;
          gps[aid]['cnt']++;      
        }
      }
      var gpsa = [];
      for ( var k in gps ) {
        gpsa.push(gps[k]);
      }
      gpsa.sort(function(a,b) {
        var an = a.name.toLowerCase();
        var bn = b.name.toLowerCase();
        if ( an > bn ) return 1;
        else if ( an < bn ) return -1;
        return 0;
      });
      this[key] = gpsa;
      return gpsa; 
    },
    getGroupByAirline: function() {
      return this.groupBy('_groupByAirlines','getAirlines',['img']);
    },
    getGroupByDepAirport: function() {
      return this.groupBy('_groupByDepAirports','getDepAirport');
    },
    getGroupByArvlAirport: function() {
      return this.groupBy('_groupByArvlAirports','getArvlAirport');
    },
    getGroupByStopAirport: function() {
      return this.groupBy('_groupByStopAirports','getStopAirports');
    },
    getGroupByAliance: function() {
      return this.groupBy('_groupByAliances','getAliances');
    },
    getGroupByGate: function() {
      return this.groupBy('_groupByGates','getGates');
    },

  });

  return flightsModel;
});
