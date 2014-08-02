define([
  'jquery',
  'underscore',
  'backbone',
  'searchFormView',
  'flightsView',
  'filterView'
], function($, _, Backbone,SearchFormView,FlightsView,FilterView){
  
  var resultsView = SearchFormView.extend({
    bindfields: ['adults','childs','infants','flclass'],
    initialize: function(ops) {
      resultsView.__super__.initialize.apply(this, arguments);

      this.$menuCtrls = $(".bottom i");

      this.filterModel = ops.flightsModel.filterModel; // new FilterModel();

      this.flightsView = new FlightsView({
        el:$("#flightsList"),
        model: ops.flightsModel,
        filterModel:this.filterModel,
      });

      this.filterView = new FilterView({
        model: ops.flightsModel,
        filterModel:this.filterModel
      });
      var m = this.model;
      for ( var i = 0 ; i < this.bindfields.length ; i++) {
        var f = this.bindfields[i];
        $("#"+f).val(this.model.get(f)).change(
          function(e) {
            m.set(e.target.id,$(e.target).val());
          }
        )
      }
      if ( m.get("adults") != 1 || m.get("childs") != 0 ||
        m.get("infants") != 0 || m.get("flclass") != "economy") {
        $("#extraFields").show();
        $("i.fa",this.$menuCtrls).removeClass("fa-arrow-down").addClass("fa-arrow-up");
        $("i.fa",this.$menuCtrls).removeClass("fa-arrow-up").addClass("fa-arrow-down");      
      }

      this.$menuCtrls.click(function() {
        var $t = $("#extraFields");
        if ( $t.is(":hidden")) {
          $t.slideDown();
          $("i.fa",this.$menuCtrls).addClass("fa-arrow-down").removeClass("fa-arrow-up");
          $("i.fa",this.$menuCtrls).addClass("fa-arrow-up").removeClass("fa-arrow-down");
        } else {
          $t.slideUp();
          $("i.fa",this.$menuCtrls).removeClass("fa-arrow-down").addClass("fa-arrow-up");
          $("i.fa",this.$menuCtrls).removeClass("fa-arrow-up").addClass("fa-arrow-down");
        }
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
          'from' : m.get("from").get("iata"),
          'adults' : m.get("adults"),
          'childs' : m.get("childs"),
          'infants' : m.get("infants"),
          'flclass' :m.get("flclass")
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
