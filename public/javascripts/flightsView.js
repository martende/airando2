define([
  'jquery',
  'underscore',
  'backbone',
  'flightView'
], function($, _, Backbone,FlightView){

  var flightsView = Backbone.View.extend({

    initialize: function(ops) {
      this.maxticks = 5;
      this.tick     = 0 ;
      this.rendered = 0;
      this.$errorInfo = $("#errorInfo");
      this.$noroutesInfo = $("#noroutesInfo");
      
      this.$progressBar = $("#progressBar");
      this.$progress = $(".progress-bar",this.$progressBar);

      this.listenTo(this.model, 'add', this.addOne);

      this.listenTo(this.model, 'progress-tick',this.progressTick);
      this.listenTo(this.model, 'progress-done',this.progressDone);
      this.listenTo(this.model, 'progress-error',this.progressError);

    },

    addOne: function(flitem) {
      if ( this.rendered >= 10 ) return;
      this.rendered++;

      var view = new FlightView({model: flitem,flightsModel:this.model});
      this.$el.append(view.render().el);
    },

    progressError:function(error) {
      this.$errorInfo.fadeIn();
      this.$progressBar.fadeOut();
    },

    progressDone:function() {
      var self = this;
      this.$progress.css("width","100%");

      this.$progress.one('transitionend webkitTransitionEnd MSTransitionEnd oTransitionEnd', function(e) {
        self.$progressBar.animate({ height: 0, opacity: 0 }, 'slow',function() {
          self.$progressBar.hide();

          if ( self.model.length == 0 ) {
            self.$noroutesInfo.fadeIn();
          }

        });
      });

    },
    
    progressTick:function() {
      this.tick++;
      if ( this.tick == this.maxticks) {
        this.maxticks+=2;
      }
      this.$progress.css("width",(this.tick*100/this.maxticks) + "%");
    },


  });

  return flightsView;
});
