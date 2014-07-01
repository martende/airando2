define([
  'underscore',
  'backbone',
  'locModel',
  'datepicker'
], function( _, Backbone,LocModel){
  var indexModel = Backbone.Model.extend({
    defaults: {
        'lang':'en',
        'curency': 'eur',
        'arrival': null,
        'departure': null,
        'from' :  new LocModel(),
        'to' :  new LocModel(),
        'traveltype': 'return'
    },
    setDate:function(d,k,o,m) {
      var v = d[k];
      var r;
      var border = (m || new Date());
      if ( v ) {
        r = v.match(/^(\d\d\d\d)(\d\d)(\d\d)$/);
        if ( r ) {
          var d1 = new Date(r[1],r[2]-1,r[3]);
          if ( d1 && d1.getTime() >= border.getTime() ) {
            this.set(k,d1);
            return;
          }
        }
      }
      var d1 = border;d1.setDate(d1.getDate() + o);  
      this.set(k,d1);
    },
    initialize: function(d) {
      this.setDate(d,'departure',7);
      this.setDate(d,'arrival',7,this.get('departure'));

      if ( 'to' in d) this.set('to',new LocModel(d['to']));
      if ( 'from' in d) this.set('from', new LocModel(d['from']));

      this.currency_rates = {};
    },
    updateCurrenciesInfo: function(crnc) {
      for ( k in crnc) {
        this.currency_rates[k] = crnc[k];
      }
    },

    getPriceText:function(val,cur) {

      var currentCurrency = this.get("currency");
      var ratio = 1;
      var cr = this.currency_rates;
      if ( currentCurrency != cur) {
        if (! (currentCurrency in cr) ) {
          currentCurrency = 'usd';
          if (! (currentCurrency in cr) ) cur = 'eur';
          if (currentCurrency in cr) {
            ratio = cr[cur] 
          } else {
            currentCurrency = cur;
          };
        } else {
          ratio = cr[currentCurrency] ;
        }
      }

      val = Math.round(val / ratio ) ;
      if ( currentCurrency == "usd") {
        return val + "$";
      } else if ( currentCurrency == "rub") {
        return val + "<i class=\"fa fa-rub\"></i>";
      } else if ( currentCurrency == "eur") {
        return val + "€";
      } else if ( currentCurrency == "gbp") {
        return val + "£";
      }
      
      return val;
    },

    getDayOfWeek: function(unix_timestamp) {
      var d = new Date(unix_timestamp*1000);
      return $.fn.datepicker.dates[this.get('lang')]['days'][d.getDay()];
    },

    humanizeDate: function(unix_timestamp) {
      var d = new Date(unix_timestamp*1000);
      
      return $.fn.datepicker.DPGlobal.formatDate(
        d,
        $.fn.datepicker.dates[this.get('lang')]['format'],
        this.get('lang')
      );
    },

    getTimeHtml: function(unix_timestamp) {
      var lang = this.get('lang');
      var date = new Date(unix_timestamp*1000);
      var hours = date.getHours();
      var minutes = date.getMinutes();
      
      if ( minutes < 10) minutes = "0"+minutes;

      if ( lang == 'ru' || lang == 'de') {
        if ( hours   < 10) hours = "0"+hours;
        return "<time>"+hours +":" +minutes + "</time>";
      } else {
        var ampm;
        if (hours <= 12 ) {
          ampm = "<span class=\"ampm\">am</span>" ;
        } else {
          hours -= 12;
          ampm = "<span class=\"ampm\">pm</span>";
        };
        if ( hours   < 10) hours = "0"+hours;
        return "<time>"+hours +":" +minutes + ampm +"</time>" ;  
      }

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

      } else if ( lang == "de") {
        if ( h == 1) {
          o = h + " Stunde";
        } else if ( h > 1 ) {
          o = h + " Stunden";
        }
        if ( m == 1) {
          o += " " + m + " Minute";
        } else if ( m > 1 ) {
          o += " " + m + " Minuten";
        }
      } else {
        if ( h == 1 || ( h10 == 1 && h > 20) ) {
          o = h + " hour";
        } else if ( h > 1 ) {
          o = h + " hours";
        }
        if ( m == 1 || ( m10 == 1 && h > 20) ) {
          o += " " + m + " minute";
        } else if ( m > 1 ) {
          o += " " + m + " minutes";
        }
      }
      return o;
    }

  });

  return indexModel;
});
