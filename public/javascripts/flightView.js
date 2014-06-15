define([
  'jquery',
  'underscore',
  'backbone',
  'moment',
  'i18n',
  'slick'
], function($, _, Backbone,moment,__){

  var flightsView = Backbone.View.extend({
    tagName: 'a',
    initialize: function(ops) {
      this.flightsModel = ops.flightsModel;
      this.dateFormat =  "h:mm D MMMM YYYY";
      
      this.$el.addClass("list-group-item row flight")
      //this.listenTo(this.model, 'add', this.addOne);
    },

    // Re-render the titles of the todo item.
    render: function() {
      this.$el.empty();
      var df = this.model.get("direct_flights");
      var trfr = this.renderMainFlight(df);
      var header = this.renderHeader(df);
      this.$el.append(header);
      this.$el.append(trfr);
      //this.$el.html(this.template(this.model.toJSON()));
      return this;
    },
    
    renderHeader: function(df) {
      var el = $("<div/>",{class:"header"});

      var np = this.model.get("native_prices");
      var used_gates = _(np).keys();
      var gates = this.flightsModel.gates;

      used_gates = used_gates.sort(function(a,b) {
        if ( np[a] > np[b]) return 1;
        if ( np[a] < np[b]) return -1;
        return 0;
      });

      var bestPrice = np[used_gates[0]];

      var b1 = $("<button/>",{
        class:"btn  btn-info buy",
        type:"button",
        html: [
          $("<span/>",{class:"glyphicon glyphicon-shopping-cart"}),
          " "+ __("Buy") + " ",
          this.createPriceHtml(bestPrice,'rub')
        ]
      });

      

      var g0 = used_gates.shift();
      var gel = this.generateGel(gates[g0].label,np[g0]).addClass("main");

      var gateEls = [gel];
      if (used_gates.length ) {
        gateEls.push(this.prepareSlick(used_gates));
      }
      var mainav = df[0].airline;
      var aviaimg = $("<img/>",{src:"/assets/images/av/"+mainav+".webp"});

      var ip = $("<div/>",{class:"buttonholder",html:[b1]});
      var p1 = $("<div/>",{class:"gatesholder",html:gateEls});
      var d0 = $("<div/>",{class:"imgholder",html:[aviaimg]});
      


      el.append([ip,p1,d0]);

      return el;
    },
    createPriceHtml:function(val,cur) {
      var el = $("<div/>",{class: 'price','data-cur' : cur,'data-val':val});
      el.html(this.flightsModel.indexModel.getPriceText(val,cur));
      return el;
    },
    generateGel: function(label,price) {
      var gel = $("<div/>",{class: 'gate',
        html: [$("<div/>",{class: 'gate-c',
          html: [
            $("<div/>",{class: 'label',html:label}),
            this.createPriceHtml(price,'rub')
          ]})
        ]
      });
      return gel;
    },

    prepareSlick: function(glist) {
      var slickWrpr = $("<div />",{class:"slick-wrpr"});
      var slick = $("<div />",{class:""});
      var np = this.model.get("native_prices");
      var gates = this.flightsModel.gates;
      var im = this.flightsModel.indexModel;
      for (var i = 0 ; i < glist.length ; i++) {
        var g = glist[i];
        if (g in gates) {
          var gel = this.generateGel(gates[g].label,np[g]);
          slick.append(gel);
        }
      }
      slickWrpr.append(slick);
      slick.slick({
        infinite: false,
        slidesToShow: 3,
        slidesToScroll: 3
      });

      return slickWrpr;
    },
    renderMainFlight: function(df) {
      var f = df[0];
      var l = df[df.length-1];
      
      var fromName = f.fromName;
      var toName   = l.toName;
      var momD     =  moment.unix(f.departure);
      var momA     =  moment.unix(l.arrival);
      var fromTime = momD.format(this.dateFormat);
      var toTime   = momA.format(this.dateFormat);

      var infoText = 
        this.humanizeDuration(f.arrival - f.departure) + 
        ", "+
        this.humanizePeresadka(df.length-1);

      var el = $("<div/>",{class:"row main v-center"});

      var icon = $("<span/>",{class: "glyphicon glyphicon-plane"});

      var p1n = $("<div/>",{class:"name",html:fromName});
      var p2n = $("<div/>",{class:"name",html:toName});
      var p1d = $("<div/>",{class:"name",html:fromTime});
      var p2d = $("<div/>",{class:"name",html:toTime});


      var ip = $("<div/>",{class:"col-sm-1",html:[icon]});
      var p1 = $("<div/>",{class:"col-sm-3",html:[p1n,p1d]});
      var d0 = $("<div/>",{class:"col-sm-5 textinfo",html:[infoText]});
      var p2 = $("<div/>",{class:"col-sm-3",html:[p2n,p2d]});
      


      el.append([ip,p1,d0,p2]);

      return el;
    },

    humanizeDuration: function(d) {
      var lang = window.initData.lang;
      var h = Math.floor(d / 3600);
      var m = Math.round((d - h *3600) / 60);
      var o = "";
      var h10 = h % 10;
      var m10 = m % 10;
      if ( lang == 'ru') {
        if ( h10 == 1 && h != 11) {
          o = h + " час";
        } else if ( h10 >= 2 && h10 <= 4 && (h < 10 || h > 20) )  {
          o = h + " часа";
        } else if ( h != 0 ) {
          o = h + " часов";
        }
        if ( m10 == 1 && m!=11) {
          o += " " + m + " минута";
        } else if ( m10 >= 2 && m10 <= 4 && (m < 10 || m > 20))  {
          o += " " + m + " минуты";
        } else if ( m != 0 ) {
          o += " " + m + " минут";
        }
        o += " в пути"
      } else if ( lang == "de") {
        if ( h == 1) {
          o = h + " Stunde";
        } else if ( h > 1 ) {
          o = h + " Stunden";
        }
        if ( m == 1) {
          o += " " + m + " Minute";
        } else if ( h > 1 ) {
          o += " " + m + " Minuten";
        }
      } else {
        if ( h == 1 || ( h10 == 1 && h > 20) ) {
          o = h + " hour";
        } else if ( h > 1 ) {
          o = h + " hour";
        }
        if ( m == 1 || ( m10 == 1 && h > 20) ) {
          o += " " + m + " minute";
        } else if ( h > 1 ) {
          o += " " + m + " minutes";
        }
      }
      return o;
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

  return flightsView;
});
