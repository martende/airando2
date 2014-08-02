define([
  'jquery',
  'underscore',
  'backbone',
  'flightView',
  'slider',
  'endless'
], function($, _, Backbone,FlightView){

  var flightsView = Backbone.View.extend({

    initialize: function(ops) {
      this.lastSort = "";
      this.firstRendered = 30;
      this.renderedIdx = 0;

      this.headerInited = false;

      this.filterModel = ops.filterModel;

      this.maxticks = 5;
      this.tick     = 0 ;

      this.$flitems = $("#flightsItems");
      this.$errorInfo = $("#errorInfo");
      this.$noroutesInfo = $("#noroutesInfo");
      this.$headers = $("#flightsHeader");
      this.$progressBar = $("#progressBar");
      this.$progress = $(".progress-bar",this.$progressBar);


      this.listenTo(this.model, 'add', this.addOne);
      this.listenTo(this.model, 'remove', this.removeOne);
      this.listenTo(this.model, 'timetable', this.addTickets);

      this.listenTo(this.model, 'tick',this.progressTick);
      this.listenTo(this.model, 'done',this.progressDone);
      this.listenTo(this.model, 'error',this.progressError);

      this.listenTo(this.filterModel,"change",this.onFilterChanged);

      this.renderedModels = {};
    },

    onScroll: function() {
      var tr = 10;
      for ( var i = this.renderedIdx+1,rendered=0 ; i < this.model.length && rendered < tr ; i++, rendered++) {
        var flitem = this.model.at(i);
        if ( this.filterModel.pass(flitem)) {
          rendered++;
          flitem.view.appendTo(this.$flitems);
          this.renderedIdx = i; 
        } 
      }
    },
    onFilterChanged: function(m,v,d) {
      this.reflectHeader();
      this.$flitems.empty();
      this.renderedIdx = 0;
      var rendered = 0;
      for ( var i = 0; i < this.model.length ; i++) {
        var flitem = this.model.at(i);
        if ( rendered < this.firstRendered ) {
          if ( this.filterModel.pass(flitem)) {
            rendered++;
            this.renderedIdx = i; 
            flitem.view.appendTo(this.$flitems);
          } 
        }
      }
    },
    reflectHeader: function() {
      var srt = this.filterModel.get("srt") || 'asc';
      var srtField = this.filterModel.get("srtField") || 'price-col';
      $(".cell").removeClass("asc").removeClass("desc");
      $(".cell."+srtField,this.$headers).addClass(srt);
      
      var lastSort = srt+":" + srtField;
      if ( lastSort !=  this.lastSort) {
        this.model.comparator = this.filterModel.getComparator();        
        this.model.sort();
        this.lastSort = lastSort;  
      }
    },
    initHeader: function() {
      var self = this;
      this.$headers.show();
      var $sortables = $(".sortable",this.$headers);
      $sortables.append($("<i>",{class:"fa fa-caret-down"}));
      $sortables.append($("<i>",{class:"fa fa-caret-up"}));
      this.reflectHeader();
      $sortables.click(function(e) {
        var $column = $(e.target).closest(".sortable");
        var classes = $column.attr('class').split(/\s+/);
        var clist = _.filter(classes,function(v) {
          return v != "sortable" && v!= "cell"
        });
        var srtField = clist[0];
        var srt = _.some(clist,function(v) { return v == "asc"}) ? "desc" : "asc";
        self.filterModel.set({
          'srtField' : srtField,
          'srt'      : srt
        })
      });
    },
    addOne: function(flitem) {
      if (  ! this.headerInited ) {
        this.headerInited = true;
        this.initHeader();
      }
      var view = new FlightView({model: flitem,flightsModel:this.model});
      view.render();
      flitem.view = view;
      /*
      if ( this.rendered < this.firstRendered ) {
        if ( this.filterModel.pass(flitem)) {
          this.rendered++;
          view.appendTo(this.$flitems);
        }
        this.renderedIdx = this.model.length - 1;
      }
      */
    },

    removeOne: function(flitem) {
      if ( flitem.cid in this.renderedModels ) {
        delete this.renderedModels[flitem.cid];
        flitem.view.remove();
        //this.$flitems.remove();
      }
    },

    addTickets: function() {
      if (  ! this.headerInited ) {
        this.headerInited = true;
        this.initHeader();
      }
      var mx = Math.min(
        this.model.length,
        Math.max(this.renderedIdx,this.firstRendered)
      );
      this.renderedIdx = 0;
      var $lastEl = null;
      for ( var i = 0 ; i < mx ; i++ ) {
        var flitem = this.model.at(i);
        if ( flitem.cid in this.renderedModels) {
          $lastEl = flitem.view.$el;
          this.renderedIdx++ ;
          continue ;
        }
        
        if ( this.filterModel.pass(flitem)) {
          this.renderedModels[flitem.cid] = 1 ;
          this.renderedIdx++ ;
          flitem.view.prependTo(this.$flitems,$lastEl);
          $lastEl = flitem.view.$el;
        }
      }
      
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
          } else {
            $(window).endlessScroll({
              //bottomPixels: 500,
              ceaseFireOnEmpty: false,
              callback: self.onScroll.bind(self)
            });
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
