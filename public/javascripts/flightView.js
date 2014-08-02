define([
  'jquery',
  'underscore',
  'backbone',
  // 'moment',
  'i18n'
  // 'slick'
], function($, _, Backbone,__){

  var templateDirectFl;
  var templateReturnFl;

  var flightView = Backbone.View.extend({
    tagName: 'a',
    initialize: function(ops) {
      this.flightsModel = ops.flightsModel;
      this.dateFormat =  "h:mm D MMMM YYYY";

      if ( ! templateDirectFl ) {
        templateDirectFl = _.template($('#tmplDirectFlight').html());
        templateReturnFl = _.template($('#tmplReturnFlight').html());
      }
      
      this.templateDirectFl = templateDirectFl;
      this.templateReturnFl = templateReturnFl;

      this.$el.addClass("list-group-item flight")
      //this.listenTo(this.model, 'add', this.addOne);
    },

    // Re-render the titles of the todo item.
    render: function() {
      this.$el.empty();
      var df = this.model.get("direct_flights");
      var rf = this.model.get("return_flights");

      var trfr;

      trfr = this.renderMainFlight(df,rf,this.model.get("tuid"));
      
      this.$el.append(trfr);

      //var header = this.renderHeader(df);
      //this.$el.append(header);
      
      return this;
    },

    createPriceHtml:function(val,cur) {
      //var el = $("<div/>",{class: 'price','data-cur' : cur,'data-val':val});
      //el.html(this.flightsModel.indexModel.getPriceText(val,cur));

      var el = "<div class=\"price\" data-cur=\""+cur+"\" data-val=\""+val+"\">" + 
        this.flightsModel.indexModel.getPriceText(val,cur) + "</div>";


      return el;
    },
    appendTo: function ($container) {
      $container.append(this.$el);
      this.bindClickEvent();
    },
    prependTo: function ($container,$lastEl) {
      if ( $lastEl ) this.$el.insertAfter($lastEl);
      else $container.prepend(this.$el);
      this.bindClickEvent();
    },
    bindClickEvent: function() {
      $(".flrow",this.$el).unbind("click").click(function(e,a) {
        var $t   = $(e.target);
        var $flr = $t.closest(".flrow");
        var $t = $flr.next();
        if ( $t.is(":hidden")) {
          $("a.buy",$flr).fadeOut();
          $t.slideDown();
        } else {
          $("a.buy",$flr).fadeIn();
          $t.slideUp();
        }
      });
    },

    renderMainFlight: function(df,rf,tuid) {
      var im = this.flightsModel.indexModel;
      var el = $("<div/>");
      var avsa = this.model.getAirlines();
      var avsh = {};      
      for ( var i = 0 ; i < avsa.length;i++) {
        avsh[avsa[i].id]=avsa[i];
      }

      var f = df[0];
      var l = df[df.length-1];
      if ( avsa.length > 4) avsa = avsa.slice(0,4);
      var bestPrice = this.createPriceHtml(
        this.model.getFullPrice(),'eur'
      );
      
      var duration = this.model.getDirectDuration();

      var innerInfo = [];
      for ( var i = 1 ; i < df.length  ; i++) {
        innerInfo.push( __("Via") + " " + this.humanizeCityVia(df[i].fromCityName) + ", " + __("stop lasts") + " " + this.humanizeDuration(df[i].delay*60) + ".");
      }

      var infoText = 
        this.humanizeDuration(duration) + " " + __("on the way") + 
        ", "+
        this.humanizePeresadka(df.length-1);
      var flc = df.length-1;
      var deproutes = this.prepareRouteDetails(avsh,df);
      
      var gatesHtml = this.prepareGatesList();
      var bestGate = gatesHtml.shift();

      var attrs = {
        'avc':avsa.length,
        'flc':flc,
        'stopsHtml' : this.createStopsHtml(flc,infoText,innerInfo,1),
        'avlines':avsa,
        'ft':im.getTimeHtml(f.departure) ,
        'tt':im.getTimeHtml(l.arrival),
        'fiata': f.origin,
        'tiata': l.destination,
        'fname': f.fromCityName,
        'tname': l.toCityName,
        'directDuration': this.humanizeDuration(duration),
        'price': bestPrice,
        'directFl': deproutes,
        'directDepDate':im.humanizeDate(f.departure),
        'directAvlDate':im.humanizeDate(l.arrival),
        'bestGate' : bestGate,
        'gates'    : gatesHtml,
        'tuid'     : tuid
      };
      var tmpl = this.templateDirectFl;
      if (rf) {
        var r_f = rf[0];
        var r_l = rf[rf.length-1];

        var r_innerInfo = [];
        for ( var i = 1 ; i < rf.length  ; i++) {
          r_innerInfo.push( __("Via") + " " + this.humanizeCityVia(rf[i].fromCityName) + ", " + __("stop lasts") + " " + this.humanizeDuration(rf[i].delay*60) + ".");
        }
        var r_infoText = 
          this.humanizeDuration(r_duration) + " " + __("on the way") + 
          ", "+
          this.humanizePeresadka(rf.length-1);
        var r_flc= rf.length-1;
        var r_duration = this.model.getReturnDuration();      
        var retroutes = this.prepareRouteDetails(avsh,rf);

        _.extend(attrs,{
          'r_flc':r_flc,
          'r_fiata': r_f.origin,
          'r_tiata': r_l.destination,
          'r_ft':im.getTimeHtml(r_f.departure),
          'r_tt':im.getTimeHtml(r_l.arrival),
          'r_stopsHtml':this.createStopsHtml(r_flc,r_infoText,r_innerInfo,-1),
          'returnDuration':this.humanizeDuration(r_duration),
          'returnDepDate':im.humanizeDate(r_f.departure),
          'returnAvlDate':im.humanizeDate(r_l.arrival),
          'returnFl': retroutes
        });
        tmpl = this.templateReturnFl;
      }
      
      el.html(tmpl(attrs));

      return el;
    },

    prepareGatesList: function() {
      var np = this.model.get("native_prices");
      var urls = this.model.get("order_urls");
      var used_gates = _(np).keys();
      var gates = this.flightsModel.gates;

      used_gates = used_gates.sort(function(a,b) {
        if ( np[a] > np[b]) return 1;
        if ( np[a] < np[b]) return -1;
        return 0;
      });

      var gatesHtml = [];
      for ( var i = 0 ; i < used_gates.length ; i++) {
        var gi = used_gates[i];
        gatesHtml.push({
          url: this.hash2url(urls[gi]),
          label: gates[gi].label,
          priceHtml: this.createPriceHtml(np[gi],'eur')
        });
      }

      return gatesHtml;
    },
    hash2url: function(sign) {
      return "/r/"+sign;
    },
    prepareRouteDetails: function(avsh,df) {
      var im = this.flightsModel.indexModel;      
      var deproutes = [];
      for ( var i = 0 ; i < df.length  ; i++) {
        deproutes.push({
          dep: im.getTimeHtml(df[i].departure),
          depDow:im.getDayOfWeek(df[i].departure),
          avl: im.getTimeHtml(df[i].arrival),
          avlDow:im.getDayOfWeek(df[i].arrival),
          fromIata: df[i].origin,
          toIata: df[i].destination,
          fromCityName: df[i].fromCityName,
          toCityName: df[i].toCityName,
          fromName: df[i].fromName,
          toName: df[i].toName,
          airlineImg: avsh[df[i].airline].img,
          airlineName: avsh[df[i].airline].name,
          aircraftName: df[i].aircraft,
          duration: this.humanizeDurationShort(df[i].duration*60),
          delay: !! df[i].delay ,
          delayHHMM: this.humanizeDurationShort(df[i].delay*60),
        });
      }
      return deproutes;
    },
    /*
    createStopsHtml: function(n,infoText,i) {
      var m = "&#xf068;";
      var o = "&#xf10c;";
      var sa = "<span class=\"arrow\">&#xf061;</span>";
      if ( n == 0 ) {
        return "<span title=\""+infoText+"\">"+sa+m+m+m+m+m+m+m+"</span>";
      } else if ( n == 1 ) {
        return "<span title=\""+infoText+"\">"+sa+m+m+"<span title=\""+i[0]+"\">"+o+"</span>"+m+m+sa+"</span>";       
      } else if ( n == 2 ) {
        return "<span title=\""+infoText+"\">"+m+m+
          "<span title=\""+i[0]+"\">"+o+"</span>"+m+
          "<span title=\""+i[1]+"\">"+o+"</span>"+
          m+m+"</span>";
      } else if ( n == 3 ) {
        return "<span title=\""+infoText+"\">&#xf068;&#xf10c;&#xf068;&#xf10c;&#xf068;&#xf10c;&#xf068;</span>";       
      }
    },*/
    createStopsHtml: function(n,infoText,i,direction) {
      var m = "<i class=\"fa fa-minus\"></i>";
      var o = "<i class=\"fa fa-circle-o\"></i>";
      var sa = "<i class=\"fa fa-chevron-"+(direction == 1 ? "right" : "left" )+"\"></i>";
      if ( n == 0 ) {
        return "<span title=\""+infoText+"\">"+m+m+m+sa+m+m+m+m+"</span>";
      } else if ( n == 1 ) {
        return "<span title=\""+infoText+"\">"+m+sa+m+m+"<span title=\""+i[0]+"\">"+o+"</span>"+m+m+sa+m+"</span>";       
      } else if ( n == 2 ) {
        return "<span title=\""+infoText+"\">"+m+sa+m+
          "<span title=\""+i[0]+"\">"+o+"</span>"+m+
          "<span title=\""+i[1]+"\">"+o+"</span>"+
          m+sa+m+"</span>";
      } else if ( n == 3 ) {
        return "<span title=\""+infoText+"\">"+m+sa+m+
          "<span title=\""+i[0]+"\">"+o+"</span>"+m+
          "<span title=\""+i[1]+"\">"+o+"</span>"+
          "<span title=\""+i[2]+"\">"+o+"</span>"+
          m+sa+m+"</span>";
      }
    },

    humanizeCityVia: function(city) {
      var lang = window.initData.lang;
      if ( lang == 'ru') {
        if ( city.charAt(city.length-1) == "а") {
          return city.substr(0,city.length-1) + "у";
        }
      }
      return city;
    },
    shortDurationHumanization: {
      'ru':["ч","м"],
      'en':["h","m"],
      'de':["s","m"],
    },
    humanizeDurationShort: function(d) {
      var lang = window.initData.lang;
      var hum = this.shortDurationHumanization[lang] || hum['en'];

      var h = Math.floor(d / 3600);
      var m = Math.round((d - h *3600) / 60);
      var o = "";
      
      if (h) o = h + hum[0];
      if (m) {
        if (o) o+=" ";
        o += m + hum[1];
      }

      return o;
    },
    humanizeDuration: function(d) {
      return this.flightsModel.indexModel.humanizeDuration(d);
    },
    
    peresadkaHumanization: {
      'ru' : ["прямой перелет","пересадка","пересадки","пересадок"],
      'de' : ["Direktflug","Stopp","Stopps"],
      'en' : ["direct flight","stop","stops"],
    },
    humanizePeresadka: function(v) {
      return this.humanize(window.initData.lang,this.peresadkaHumanization,v)
    },
    humanize: function(lang,hum,h) {
      var hum = hum[lang] || hum['en'];
      var h10 = h % 10;
      if ( lang == 'ru') {
        if ( h10 == 1 && h != 11 ) {
          o = h + " " + hum[1];
        } else if ( h10 >= 2 && h10 <= 4 && (h < 10 || h > 20) )  {
          o = h + " " + hum[2];
        } else if ( h == 0 && hum[0]) {
          o = hum[0];
        } else {
          o = h + " " + hum[3];
        }
      } else if ( lang == "de") {
        if ( h == 1) {
          o = h + " " + hum[1];
        } else if ( h > 1 ) {
          o = h + " " + hum[2];
        } else if ( h == 0 && hum[0]) {
          o = hum[0];
        }
      } else {
        if ( h == 1 || ( h10 == 1 && h > 20) ) {
          o = h + " " + hum[1];
        } else if ( h > 1 ) {
          o = h + " " + hum[2];
        } else if ( h == 0 && hum[0]) {
          o = hum[0];
        }
      }
      return o;
    }

  });

  return flightView;
});
