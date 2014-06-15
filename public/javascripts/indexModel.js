define([
  'underscore',
  'backbone',
  'locModel'
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

      if ( currentCurrency == "usd") {
        val = Math.round(val / ratio ) ;
        return val + "$";
      } else if ( currentCurrency == "rub") {
        val = Math.round(val / ratio ) ;
        return val + "<i class=\"fa fa-rub\"></i>";
      } else if ( currentCurrency == "eur") {
        val = Math.round(val / ratio ) ;
        return val + "€";
      } else if ( currentCurrency == "gbp") {
        val = Math.round(val / ratio ) ;
        return val + "£";
      }
      
      return val;
    },

  });

  return indexModel;
});
