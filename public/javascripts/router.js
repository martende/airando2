// Filename: router.js
define([
  'jquery',
  //'jqueryui', // Load fast 
  'underscore',
  'backbone',
  'indexView',
  'resultsView',
  'indexModel',
  'flightsModel',
  //'flightsView',
  //'views/MapView',
  //'models/Game'
  

], function($, /*jqueryui ,*/ _ , Backbone,
  IndexView,ResultsView,
  IndexModel,FlightsModel){
  var AppRouter = Backbone.Router.extend({
    routes: {
      'result/:resultid': 'result',
      'flights/:path': 'result2',
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
      var m2 = new FlightsModel([],{idx:searchId,indexModel:m});

      new ResultsView({
        el:document,
        model: m,
        flightsModel:m2
      });

      m2.update();
    },
    
    result2: function(path) {
      var searchId = window.initData.searchId;
      var m = new IndexModel(window.initData);
      var m2 = new FlightsModel([],{idx:searchId,indexModel:m});

      new ResultsView({
        el:document,
        model: m,
        flightsModel:m2
      });

      m2.update();
    },
    

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
