// Filename: router.js
define([
  'jquery',
  //'jqueryui', // Load fast 
  'underscore',
  'backbone',
  'indexView',
  'indexModel',
  'flightsModel',
  'flightsView'
  //'views/MapView',
  //'models/Game'
  

], function($, /*jqueryui ,*/ _ , Backbone,IndexView,IndexModel,FlightsModel,FlightsView){
  var AppRouter = Backbone.Router.extend({
    routes: {
      'result/:resultid': 'result',
      '': 'index'
    },
    index: function() {
      console.log("index");
      var m = new IndexModel(window.initData);
      new IndexView({
        el:document,
        model: m
      });
    },
    result: function(searchId) {
      var m = new IndexModel(window.initData);
      new IndexView({
        el:document,
        model: m
      });
      var m2 = new FlightsModel([],{idx:searchId,indexModel:m});
      new FlightsView({
        el:$("#flightsList"),
        model: m2
      });
      m2.update();
    }
  });

  var initialize = function(){
    var app_router = new AppRouter;
    Backbone.history.start({
      pushState:true
    });
  };
  return {
    initialize: initialize
  };
});
