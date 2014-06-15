define([
  'underscore',
  'backbone',
], function( _, Backbone){
  var locModel = Backbone.Model.extend({
    defaults: {
        value: []
    }
  });

  return locModel;
});
