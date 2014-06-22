define([
  'jquery',
  'underscore',
  'backbone',
  'locView',
  // Extra deps
  'datepicker',
  'jqueryui',
  'vendor/jquery.cookie'
], function($, _, Backbone,LocView){
  console.log("indexView", $.ui);

  var searchFormView = Backbone.View.extend({

    initialize: function() {
      var mind = new Date();mind.setDate(mind.getDate() );
      var maxd = new Date();maxd.setDate(maxd.getDate() + 90);
      this.$(".date-group").datepicker({
        autoclose: true,
        startDate: mind,
        endDate:   maxd,
        language: this.model.get('lang')
      });

      var dp = this.$(".date-group").data('datepicker');
      dp.pickers[0].setDates(this.model.get('departure'));
      dp.pickers[1].setDates(this.model.get('arrival'));

      this.$(dp.inputs[0]).on('changeDate',this.onChangeDateDep.bind(this));
      this.$(dp.inputs[1]).on('changeDate',this.onChangeDateArvl.bind(this));
      this.fromLocView = new LocView({el: $("#fromInp"),model: this.model.get('from')});
      this.toLocView   = new LocView({el: $("#toInp"),model: this.model.get('to')});

      this.$travelType= this.$("#travelType");
      this.$searchButton = this.$("#search");
      this.$arvlDate = this.$("#arvlDate");

      this.listenTo(this.model,"change:traveltype",this.onTravelType);
      this.listenTo(this.model,"change:currency",this.onCurrency);
      
      this.$travelType.click(this.onTravelTypeClick.bind(this));
      
      this.$("#currencyList ul li a").click(this.onCurrencyClick.bind(this));

      this.$searchButton.click(this.onSubmit.bind(this));

      this.$("form").submit(this.onSubmit.bind(this));

      this.listenTo(this.model.get("to"),"change",function() {
        this.$("#toInp").parent().removeClass("has-error");
      }.bind(this));
      
      this.listenTo(this.model,"change:traveltype",function() {
        this.$arvlDate.parent().removeClass("has-error");
      }.bind(this));

      this.listenTo(this.model,"change:arrival",function() {
        this.$arvlDate.parent().removeClass("has-error");
      }.bind(this));

      this.bindSessionListeners();

      this.onTravelType();
      this.onCurrency();
    },
    yyyymmdd: function(d) {
      var yyyy = d.getFullYear().toString();
      var mm = (d.getMonth()+1).toString(); // getMonth() is zero-based
      var dd  = d.getDate().toString();
      return yyyy + (mm[1]?mm:"0"+mm[0]) + (dd[1]?dd:"0"+dd[0]); // padding
    },
    bindSessionListeners: function() {
      this.listenTo(this.model,"change:traveltype",function(m) {$.cookie('traveltype', m.get('traveltype'));});
      this.listenTo(this.model,"change:currency",function(m) {$.cookie('currency', m.get('currency'));});

      this.listenTo(this.model,"change:departure",function(m) {$.cookie('departure', this.yyyymmdd(m.get('departure')));});
      this.listenTo(this.model,"change:arrival",function(m) {$.cookie('arrival', this.yyyymmdd(m.get('arrival')));});

      this.listenTo(this.model.get("to"),"change:iata",function(m) {$.cookie('to', m.get('iata'));});
      this.listenTo(this.model.get("from"),"change:iata",function(m) {$.cookie('from', m.get('iata'));});
    },

    onTravelTypeClick: function(e) {
      this.model.set("traveltype",this.$travelType.hasClass("active") ? "return" : "oneway" ,true);
    },

    onTravelType: function(m,f,isView) {
      var tt = this.model.get("traveltype");
      if ( ! isView ) {
        if ( tt == 'oneway' )
          this.$travelType.addClass("active");
        else 
          this.$travelType.removeClass("active");
      }
      
      this.$arvlDate.attr("disabled",tt == 'oneway');

    },
    onCurrencyClick: function(e) {
      var newcur = $(e.target).closest("li").attr("data-cur");
      this.model.set("currency",newcur);
    },

    onCurrency: function(m,f,isView) {
      var cur = this.model.get("currency");
      console.log("cur",cur);
      this.$("#currencyList ul li").removeClass("active");
      var active = this.$("#currencyList ul li[data-cur=\""+cur+"\"]");
      active.addClass("active");
      this.$("#currencyList > a > span").html($("span",active).html());

      this.reloadCurrencies(cur);
    },

    reloadCurrencies: function() {
      var self = this;
      $(".price").each(function(i,e) {
        var $e = $(e);
        var currency = $e.attr("data-cur");
        var val = $e.attr("data-val");
        $e.html(self.model.getPriceText(val,currency));
      });
    },

    onChangeDateDep: function(evt) {
        this.model.set('departure',evt.date,true);
    },

    onChangeDateArvl: function(evt) {
        this.model.set('arrival',evt.date,true);
    }

  });

  return searchFormView;
});
