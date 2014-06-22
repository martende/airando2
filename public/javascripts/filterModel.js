define([
  'underscore',
  'backbone',
], function( _, Backbone){
  var m = Backbone.Model.extend({
    defaults: {
        'airport' : null,
        'stops'   : 2,
    },

    pass: function(item) {
    	return this.passAirline(item) && this.passAliance(item)
        && this.passDepAirports(item) && this.passArvlAirports(item)
        && this.passStopAirports(item) && this.passStops(item) 
        && this.passDuration(item) && this.passPrice(item) 
        && this.passDepTime(item) && this.passArvlTime(item)
        && this.passStopDuration(item)
      ;
    },

    containsAny: function(a,s) {
		  for ( var i = 0 ; i < a.length;i++) {
        if ( s[a[i]] ) return true;
    	}
    	return false;
    },
    
    eqAny: function(a,s) {
      for ( var i = 0 ; i < a.length;i++) {
        if ( a[i] == s ) return true;
      }
      return false;
    },

    passStops: function(item) {
      var r = this.get('stops');
      return ( r == 2) || item.getStopsCnt() <= r; 
    },
    passAirline: function(item) {
    	var r = this.get('airline');
    	if ( r ) {
    		var s = item.getAirlinesSet();
    		return this.containsAny(r,s);
    	}
    	return true;
    },
    passAliance: function(item) {
      var r = this.get('aliance');
      if ( r ) {
        var s = item.getAliancesSet();
        return this.containsAny(r,s);
      }
      return true;
    },
    passDepAirports: function(item) {
      var r = this.get('depAirport');
      if ( r ) {
        var s = item.getDepAirport().id;
        return this.eqAny(r,s);
      }
      return true;
    },
    passArvlAirports: function(item) {
      var r = this.get('arvlAirport');
      if ( r ) {
        var s = item.getArvlAirport().id;
        return this.eqAny(r,s);
      }
      return true;
    },
    passStopAirports: function(item) {
      var r = this.get('stopAirport');
      if ( r ) {
        var s = item.getStopAirportsSet();
        return this.containsAny(r,s);
      }
      return true;
    },
    passDuration:function(item) {
      var r = this.get('duration');
      return ! r || (item.getAvgDuration() <= r)
    },
    passPrice:function(item) {
      var r = this.get('price');
      return ! r || (item.getFullPrice() <= r)
    },

    passDepTime: function(item) {
      var r = this.get('depTime');
      var v = item.getDepTime();
      return ! r || (  r[0] <= v && r[1] >= v )
    },

    passArvlTime: function(item) {
      var r = this.get('avlTime');
      var v = item.getArvlTime();

      return ! r || (  r[0] <= v && r[1] >= v )
    },

    passStopDuration: function(item) {
      var r = this.get('stopDuration');
      var v = item.getTotalPause();
      return ! r || (  r[0] <= v && r[1] >= v )
    }

  });

  return m;
});
