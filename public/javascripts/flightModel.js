define([
  'underscore',
  'backbone'
  ], function( _, Backbone){
  var flightModel = Backbone.Model.extend({
    defaults: {
    },

    getDepTime: function() {
      var date = new Date(this.get("direct_flights")[0].departure*1000);
      var hours = date.getHours();
      var minutes = date.getMinutes();
      return hours*3600 + minutes * 60;
    },
    getArvlTime: function() {
      var df = this.get("direct_flights");
      var date = new Date(df[df.length-1].arrival*1000);
      var hours = date.getHours();
      var minutes = date.getMinutes();
      return hours*3600 + minutes * 60;
    },
    getFullPrice: function() {
    	if ( this._total ) return this._total;
    	
    	var np = this.get("native_prices");
	    var used_gates = _(np).keys();

	    used_gates = used_gates.sort(function(a,b) {
	        if ( np[a] > np[b]) return 1;
	        if ( np[a] < np[b]) return -1;
	        return 0;
	    });

	    this._total = np[used_gates[0]];

    	return this._total;
    },

    getAvgDuration: function() {
      if ( this._avgdur ) return this._avgdur;

      var df = this.get("direct_flights");
      var rf = this.get("return_flights");

      if ( rf ) {
        this._avgdur = ( this.getDirectDuration() + this.getReturnDuration() ) / 2;
      } else {
        this._avgdur = this.getDirectDuration();
      }

      return this._avgdur;

    },

    getDirectDuration: function() {
      if ( this._directdur ) return this._directdur;

      var duration = 0;
      var f = this.get("direct_flights");
      for ( var i = 0 ; i < f.length ; i++ ) {
        duration+=f[i].duration + f[i].delay;
      }
      duration*=60;
      this._directdur=duration;
      return duration;
    },

    getReturnDuration: function() {
      if ( this._returndur ) return this._returndur;
      var duration = 0;
      var f = this.get("return_flights");
      for ( var i = 0 ; i < f.length ; i++ ) {
        duration+=f[i].duration + f[i].delay;
      }
      duration*=60;
      this._returndur=duration;

      return duration;
    },

    getTotalPause: function() {
      if ( this._totalpause ) return this._totalpause;
      var pause = 0;
      var f = this.get("direct_flights");
      if ( f )
        for ( var i = 0 ; i < f.length ; i++ ) {
          pause+=  f[i].delay;
        }
      f = this.get("return_flights");
      if ( f )
        for ( var i = 0 ; i < f.length ; i++ ) {
          pause+=  f[i].delay;
        }
      pause*=60;
      this._returndur=pause;

      return pause;
    },
    getStopsCnt: function() {
      var df = this.get("direct_flights");
      var rf = this.get("return_flights");
      if ( rf ) {
        return Math.max(df.length-1,rf.length-1);
      }
      return df.length-1;
    },

    getDepAirport: function() {
      var df = this.get("direct_flights");
      return {
        id: df[0].origin,
        name: df[0].fromName,
      }
    },
    getArvlAirport: function() {
      var df = this.get("direct_flights");
      return {
        id: df[df.length-1].destination,
        name: df[df.length-1].toName,
      }
    },
    getStopAirportsSet: function() {
      if ( this._stopAirportsSet ) return this._stopAirportsSet;
      var a = this.getStopAirports(),ah = {};
      for (var i = 0;i < a.length;i++ ) {
        ah[a[i].id]=1;
      }
      this._stopAirportsSet=ah;
      return ah;
    },
    getStopAirports: function() {
      if ( this._stopairprts ) return this._stopairprts;
      var d = {};
      var avs = {};
      var avsa = [];
      var df = this.get("direct_flights");
      var rf = this.get("return_flights");
      var a = [df];
      if ( rf ) a.push(rf);

      for ( var i = 0; i < a.length ; i++) {
        for ( var j = 1 ; j < a[i].length ; j++ ) {
          var an = a[i][j].origin;
          if (! (an in avs ) ) {
            
            avsa.push({
              'id':an,
              'name':a[i][j].fromName
            });
            avs[an]=1;
          }  
        }
      }
      this._stopairprts = avsa;
      return this._stopairprts;

    },
    getAirlinesSet: function() {
      if ( this._airlinesSet ) return this._airlinesSet;
      var a = this.getAirlines(),ah = {};
      for (var i = 0;i < a.length;i++ ) {
        ah[a[i].id]=1;
      }
      this._airlinesSet=ah;
      return ah;
    },
    getAirlines: function() {
      if ( this._airlines ) return this._airlines;
      var avs = {};
      var avsa = [];
      var df = this.get("direct_flights");
      var rf = this.get("return_flights");
      var a = [df];
      if ( rf ) a.push(rf);

      for ( var i = 0; i < a.length ; i++) {
        for ( var j = 0 ; j < a[i].length ; j++ ) {
          var an = a[i][j].airline;
          if (! (an in avs ) ) {
            var t = "";
            if ( an in this.collection.airlines ) {
              t = this.collection.airlines[an].name;
            }
            
            avsa.push({
              'id':an,
              'img':"/assets/images/avs/"+an+".png",
              'name':t
            });
            avs[an]=1;
          }  
        }
      }
      this._airlines = avsa;
      return this._airlines;
    },
    getAliancesSet: function() {
      if ( this._aliancesSet ) return this._aliancesSet;
      var a = this.getAliances(),ah = {};
      for (var i = 0;i < a.length;i++ ) {
        ah[a[i].id]=1;
      }
      this._aliancesSet=ah;
      return ah;      
    },
    getAliances: function() {
      if ( this._aliances ) return this._aliances;

      var avs = {};
      var avsa = [];
      var df = this.get("direct_flights");
      var rf = this.get("return_flights");
      var a = [df];
      if ( rf ) a.push(rf);

      for ( var i = 0; i < a.length ; i++) {
        for ( var j = 0 ; j < a[i].length ; j++ ) {
          var an = "other";
          var av = a[i][j].airline;

          if ( av in this.collection.airlines && this.collection.airlines[av]['alliance_name']) {
            an = this.collection.airlines[av]['alliance_name'];
          
            if (! (an in avs ) ) {
    
              avsa.push({
                'id':an,
                'name':an
              });

              avs[an]=1;
            }  
          }
        }
      }

      this._aliances = avsa;
      return this._aliances;      
    }

  });

  return flightModel;
});
