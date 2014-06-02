// Filename: router.js
define([
  'jquery',
  'underscore',
  'backbone',
  //'views/MapView',
  //'models/Game'
], function($, _, Backbone){
  var AppRouter = Backbone.Router.extend({
    routes: {
      '*actions': 'index'
    },
    index: function() {
      //this.game = new Game({id:10});
      //this.mapview  = new MapView({model:this.game,el:document.getElementById("map")});
    }
  });

  var initialize = function(){
    var app_router = new AppRouter;
    Backbone.history.start();
  };
  return {
    initialize: initialize
  };
});
