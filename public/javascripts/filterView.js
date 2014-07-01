define([
  'jquery',
  'underscore',
  'i18n',
  'backbone',
  'filterModel',
  
  'slider'
], function($, _,__, Backbone,FilterModel){

  var filterView = Backbone.View.extend({

    initialize: function(ops) {

      this.filterModel = ops.filterModel;

      this.$totalW = $("#wSlidersHeader .count");
      
      this.$slFlDuration = $("#slFlDuration");
      this.$slDepTime = $("#slDepTime");
      this.$slAvlTime = $("#slAvlTime");
      this.$slPrice = $("#slPrice");
      this.$slStopDuration = $("#slStopDuration");

      this.listenTo(this.model, 'timetable',this.progressData);      

      this.templateAirline = _.template($('#tmplAirline').html());
      this.templateAirport = _.template($('#tmplAirport').html());
      this.templateAliance = _.template($('#tmplAliance').html());

      this.initSliders();
    },

    initSliders: function() {   
      var im = this.model.indexModel;
      var fm = this.filterModel;
      this.$slFlDuration.slider({
        value: 600 ,
        orientation: "horizontal",
        min: 0,
        max: 600,
        step: 300,
        formater: this.hhmmFormatter,
      }).on('slideStop',function(e) {
        fm.set('duration',e.value);
      });

      this.$slPrice.slider({
        value: 20 ,
        orientation: "horizontal",
        min: 0,
        max: 20,
        step: 10,
        formater: function(v) {
          return im.getPriceText(v);
        }
      }).on('slideStop',function(e) {
        fm.set('price',e.value);
      });

      this.$slDepTime.slider({
        value: [0,86400],
        min: 0,
        max: 86400,
        step: 1800,
        orientation: "horizontal",
        formater: this.hhmmFormatter
      }).on('slideStop',function(e) {
        fm.set('depTime',e.value);
      });

      this.$slAvlTime.slider({
        value: [0,86400],
        min: 0,
        max: 86400,
        step: 1800,
        orientation: "horizontal",
        formater: this.hhmmFormatter
      }).on('slideStop',function(e) {
        fm.set('avlTime',e.value);
      });

      this.$slStopDuration.slider({
        value: [0,86400] ,
        orientation: "horizontal",
        min: 0,
        max: 86400,
        step: 1800,
        formater: this.hhmmFormatter
      }).on('slideStop',function(e) {
        fm.set('stopDuration',e.value);
      });

    },

    minPriceShown: false,
    processBestPriceWidget: function() {
      var self = this;
      var im = this.model.indexModel;
      var m = this.model.getMinPriceObj();
      if ( m ) {
        var price = m.getFullPrice();
        var vhtml = im.getPriceText(price);
        $("#wBestPrice .price").each(function(i,o) {
          var $o = $(o);
          $o.attr('data-val',price);
          $o.html(vhtml);
        });
        var dur = m.getAvgDuration();
        var thtml = im.humanizeDuration(dur);
        $("#wBestPrice time").each(function(i,o) {
          var $o = $(o);
          $o.html(thtml);
        });
        if ( ! this.minPriceShown ) {
          this.minPriceShown = true;
          $("#wBestPrice").fadeIn();
        }
      }

      $("#wBestPrice button").unbind("click").click(function() {
        self.filterModel.set({
          srtField:'price-col',
          srt:'asc'
        });
        
        var $flr = $("#flightsItems .flrow:first");
        var $t = $flr.next();
        if ( $t.is(":hidden")) {
          $("button",$flr).fadeOut();
          $t.slideDown();
        } else {
          $flr.add($flr.next()).effect( "highlight", 
          {}, 1000 );
        }
      });
    },

    minTimeShown: false,
    processBestTimeWidget: function() {
      var m = this.model.getMinTimeObj();
      var im = this.model.indexModel;
      var self = this;

      if ( m ) {
        var price = m.getFullPrice();
        var vhtml = im.getPriceText(price);
        $("#wBestTime .price").each(function(i,o) {
          var $o = $(o);
          $o.attr('data-val',price);
          $o.html(vhtml);
        });
        var dur = m.getAvgDuration();
        var thtml = im.humanizeDuration(dur);
        $("#wBestTime time").each(function(i,o) {
          var $o = $(o);
          $o.html(thtml);
        });
        if ( ! this.minTimeShown ) {
          this.minTimeShown = true;
          $("#wBestTime").fadeIn();
        }
      }

      $("#wBestTime button").unbind("click").click(function() {
        self.filterModel.set({
          srtField:'duration',
          srt:'asc'
        });
        
        var $flr = $("#flightsItems .flrow:first");
        var $t = $flr.next();
        if ( $t.is(":hidden")) {
          $("button",$flr).fadeOut();
          $t.slideDown();
        } else {
          $flr.add($flr.next()).effect( "highlight", 
          {}, 1000 );
        }
      });
    },

    processGroupByStops: function() {
      var groupByStops = this.model.getGroupByStops();
      var im = this.model.indexModel;
      var self = this;
      $("#groupByStops").children().each(function(i,v) {
        var $p = $(".price",v);
        if ( ! groupByStops[i] ) {
          $("input",v).attr("disabled",true);
          $(v).addClass("disabled");
          $p.hide();
        } else {
          $("input",v).attr("disabled",false);
          $(v).removeClass("disabled");

          $p.attr("data-val",groupByStops[i].minPrice);
          $p.html( im.getPriceText(groupByStops[i].minPrice ) );
          $p.show();
        }
      });
      var st = self.filterModel.get('stops');
      $("#groupByStops input").each(function(i,e) {
        $(e).prop("checked",e.value == st);
      });

      $("#groupByStops input").click(function(e) {
        var $e=$(e.target);
        self.filterModel.set('stops',e.target.value);
      });
    },
    

    processSliders: function() {
      if (! this.model.length ) return;
      var im = this.model.indexModel;

      var minTime = this.model.getMinTimeObj().getAvgDuration();
      var maxTime = this.model.getMaxTimeObj().getAvgDuration();

      var minPrice = this.model.getMinPriceObj().getFullPrice();
      var maxPrice = this.model.getMaxPriceObj().getFullPrice();

      $("#wSliders").removeClass("disabled");

      var mm = this.calcDurationMinMax(minTime,maxTime,300);
      
      this._processSlider(this.$slFlDuration,mm);

      mm = this.calcDurationMinMax(minPrice,maxPrice,10);

      this._processSlider(this.$slPrice,mm);

      var maxPause = this.model.getMaxPauseObj().getTotalPause();

      this._processSlider2(this.$slStopDuration,[0,maxPause]);

    },

    _processSlider: function($slider,mm) {
      var curmax  = $slider.data('slider').max;
      var curval = $slider.data('slider').value[0];

      $slider.slider('setMinMax',mm); 

      if ( curval == curmax) $slider.slider('setValue',mm[1]);

    },

    _processSlider2: function($slider,mm) {
      var curmax  = $slider.data('slider').max;
      var curmin  = $slider.data('slider').min;
      var curval = $slider.data('slider').value[1];

      $slider.slider('setMinMax',mm); 

      if ( curval == curmax) $slider.slider('setValue',[ curmin,mm[1] ] );

    },

    _processLists: function(f,$el,tmpl,filter) {
      var v = f.apply(this.model);
      var im = this.model.indexModel;
      var ret = [];
      var self = this;

      ret.push({
        id: 0,
        name: __("All"),
        img: null,
        price: null
      });

      for ( var i = 0; i < v.length ; i++) {
        var priceText =  im.getPriceText(v[i].minPrice );
        ret.push({
          id: v[i].id,
          name: v[i].name,
          img: v[i].img,
          priceText: priceText,
          price: v[i].minPrice
        });
      }


      $el.html(tmpl({
        ret: ret
      }));

      $("input",$el).prop("checked",true);

      $("input",$el).click(function(e) {
        var $e=$(e.target);
        if ( e.target.value == "0") {
          if ( $e.prop("checked") ) {
            $e.parent().nextAll().children("input").prop("checked",true);
          } else {
            $e.parent().nextAll().children("input").prop("checked",false);
          }
          self.filterModel.set(filter,null);
        } else {
          var canall = _.all($("input[value!=\"0\"]",$e.parent().parent()).map(function(i,v) {return $(v).is(":checked")}));
          $("input[value=\"0\"]",$e.parent().parent()).prop("checked",canall);
          if ( canall ) self.filterModel.set(filter,null); else {
            var vals = $("input[value!=\"0\"]",$e.parent().parent()).map(function(i,v) {return $(v).is(":checked") ? $(v).prop("value") : null  }).get();
            self.filterModel.set(filter,vals);
          }
        }
      }); 

      $(".count",$el.prev()).html(ret.length - 1 );
    },

    progressData: function() {

      this.processBestPriceWidget();
      this.processBestTimeWidget();

      this.$totalW.html(this.model.length);

      this.processGroupByStops();
      
      this.processSliders();

      this._processLists(this.model.getGroupByAirline,
      $("#avlinesList"),this.templateAirline,'airline');

      this._processLists(this.model.getGroupByDepAirport,
      $("#depAirportsList"),this.templateAirport,'depAirport');

      this._processLists(this.model.getGroupByArvlAirport,
      $("#arvlAirportsList"),this.templateAirport,'arvlAirport');

      this._processLists(this.model.getGroupByStopAirport,
      $("#stopAirportsList"),this.templateAirport,'stopAirport');

      this._processLists(this.model.getGroupByAliance,
      $("#aliancesList"),this.templateAliance,'aliance');

    },
    hhmmFormatter: function(v) {
      var h = Math.floor(v / 3600);
      var m = Math.floor((v - h * 3600) / 60 ) ;
      if (h==0) h = "00"; else if (h < 10) h = "0" + h;
      if (m==0) m = "00"; else if (m < 10) m = "0" + m;
      return h + ":" + m;
    },

    calcDurationMinMax: function(min,max,step) {
      var step2 = step * 2; 
      var mint = -Math.floor( -min / step2 ) * step2;
      var maxt = -Math.floor( -max / step2 ) * step2;
      if ( maxt == mint) maxt += step2;      
      return [mint,maxt];
    }


  });

  return filterView;
});
