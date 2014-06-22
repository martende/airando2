define([
  'jquery',
  'underscore',
  'backbone',
  'searchFormView',
], function($, _, Backbone,SearchFormView){

  var indexView = SearchFormView.extend({
    
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

  return indexView;
});
