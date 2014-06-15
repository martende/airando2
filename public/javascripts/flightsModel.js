define([
  'underscore',
  'backbone',
  'flightModel'
], function( _, Backbone,FlightModel){
  var flightsModel = Backbone.Collection.extend({
    model: FlightModel,

    initialize: function(models, ops) {
      this.idx = ops.idx;
      this.indexModel = ops.indexModel;
      this.gates = {};
    },

    update: function() {
      var self = this;

      var cbIdx = Math.random().toString().substr(2);
      var iframe = $("<iframe/>").attr({
        "src":"/track/"+self.idx + "?cb=" + cbIdx,
        "style":"height:0px;width:0px"
      });
      var idx = self.idx;
      
      self.trigger("progress-tick");

      window['cb'+cbIdx] = function(response) {
        if ( ! response || idx != self.idx) {
          delete window['cb'+cbIdx];
          iframe.remove();
          self.trigger("progress-done");
          return;
        }

        if ( response.ok ) {
          self.trigger("progress-tick");
          if ( response.data.tickets ) 
            self.updateTimetable(response.data.tickets);
          if ( response.data.gates ) 
            self.updateGatesInfo(response.data.gates);
          if ( response.data.currency_rates ) 
            self.indexModel.updateCurrenciesInfo(response.data.currency_rates);

        } else {
          self.trigger("progress-error",response.error);
        }
      };

      $(document.body).append(iframe);

    },
    updateTimetable: function(data) {
      console.log("updateTimetable");
      data.sort(function(x,y) {
        if (x.total > y.total) return 1 ;
        if (x.total < y.total) return -1 ;
        return 0;
      });
      for ( var i = 0 ; i < data.length ; i++) {
        console.log(data[i]);
        this.merge(data[i]);
      }
    },
    merge: function(data) {
      this.add(data);
      console.log(data);
    },
    updateGatesInfo: function(g) {
      for ( var i = 0 ; i < g.length ; i++) {
        this.gates[g[i].id]=g[i];
      }
      this.trigger("gatesupdated");
    }
  });

  return flightsModel;
});
