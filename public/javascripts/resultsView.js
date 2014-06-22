define([
  'jquery',
  'underscore',
  'backbone',
  'searchFormView',
  'flightsView',
  'filterView',
  'filterModel'
], function($, _, Backbone,SearchFormView,FlightsView,FilterView,FilterModel){
  
  var resultsView = SearchFormView.extend({
    initialize: function(ops) {

      resultsView.__super__.initialize.apply(this, arguments);

      this.filterModel = new FilterModel();

      this.flightsView = new FlightsView({
        el:$("#flightsList"),
        model: ops.flightsModel,
        filterModel:this.filterModel,
      });

      this.filterView = new FilterView({
        model: ops.flightsModel,
        filterModel:this.filterModel
      });

    },

    onSubmit:function(e) {
      var bad = false;
      if ( ! this.model.get("to").get("iata") ) {
        $("#toInp").parent().addClass("has-error");
        bad = true;
      }
      if (  this.model.get("traveltype") == 'return' && ( 
          ! this.model.get('arrival') || ! this.$arvlDate.val() 
          )
        )  {
        this.$arvlDate.parent().addClass("has-error");
        bad = true;
      }
      if ( bad ) {
        e.stopPropagation();
        return false;
      }

      this.$searchButton.addClass("loading").attr("disabled",true);
      
      var m = this.model;
      $.ajax({
        url: '/start',
        type: 'post',
        data: {
          'traveltype':m.get('traveltype'),
          'departure':this.yyyymmdd(m.get('departure')),
          'arrival':this.yyyymmdd(m.get('arrival')),
          'to'   : m.get("to").get("iata"),
          'from' : m.get("from").get("iata")
        },
        success: function(response) {
          //console.log("response",response);
          window.setTimeout( function() {
            window.location.href = "/result/" + response.id;
          }, 1000 );
          
        }
      });
    }

  });

  return resultsView;
});
