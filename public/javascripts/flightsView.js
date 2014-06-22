define([
  'jquery',
  'underscore',
  'backbone',
  'flightView',
  'slider'
], function($, _, Backbone,FlightView){

  var flightsView = Backbone.View.extend({

    initialize: function(ops) {
      this.filterModel = ops.filterModel;

      this.maxticks = 5;
      this.tick     = 0 ;
      this.rendered = 0;

      this.$flitems = $("#flightsItems");
      this.$errorInfo = $("#errorInfo");
      this.$noroutesInfo = $("#noroutesInfo");
      this.$headers = $("#flightsHeader");
      this.$progressBar = $("#progressBar");
      this.$progress = $(".progress-bar",this.$progressBar);


      this.listenTo(this.model, 'add', this.addOne);

      this.listenTo(this.model, 'tick',this.progressTick);
      this.listenTo(this.model, 'done',this.progressDone);
      this.listenTo(this.model, 'error',this.progressError);

      this.listenTo(this.filterModel,"change",this.onFilterChanged);
    },

    onFilterChanged: function(m,v,d) {
      this.$flitems.empty();
      this.rendered = 0;
      for ( var i = 0; i < this.model.length ; i++) {
        var flitem = this.model.at(i);
        if ( this.filterModel.pass(flitem)) {
          this.rendered++;
          if ( this.rendered >= 30 ) return;

          this.$flitems.append(flitem.view.el);
        }
      }
    },

    addOne: function(flitem) {
      if ( this.rendered == 0) {
        this.$headers.show();//("display","block");
      }
      this.rendered++;
      var view = new FlightView({model: flitem,flightsModel:this.model});
      view.render();
      flitem.view = view;
      if ( this.rendered >= 30 ) return;
      this.$flitems.append(view.el);

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
