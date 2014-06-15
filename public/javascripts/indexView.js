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

  var indexApp = Backbone.View.extend({

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

      this.listenTo(this.model,"change:traveltype",this.onTravelType);
      this.listenTo(this.model,"change:currency",this.onCurrency);
      
    
      this.$(".traveltype-group label").click(this.onTravelTypeClick.bind(this));
      
      this.$("#currencyList ul li a").click(this.onCurrencyClick.bind(this));

      this.$("button.search").click(this.onSubmit.bind(this));
      this.$("form").submit(this.onSubmit.bind(this));

      this.listenTo(this.model.get("to"),"change",function() {
        this.$("#toInp").parent().removeClass("has-error");
      }.bind(this));
      
      this.listenTo(this.model,"change:traveltype",function() {
        this.$("#arvlDate").parent().removeClass("has-error");
      }.bind(this));

      this.listenTo(this.model,"change:arrival",function() {
        this.$("#arvlDate").parent().removeClass("has-error");
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
      var tt = this.$(e.target).find("input").attr("id");
      this.model.set("traveltype",tt,true);
    },
    onTravelType: function(m,f,isView) {
      var tt = this.model.get("traveltype");
      if ( ! isView ) {
        this.$(".traveltype-group input").attr("checked",false);
        this.$("#" + tt).attr("checked",true).parent().addClass("active");  
      }
      
      this.$("#arvlDate").attr("disabled",tt == 'oneway');

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
        var currency = $e.data("cur");
        var val = $e.data("val");
        $e.html(self.model.getPriceText(val,currency));
      });
    },
    
    onSubmit:function(e) {
      var bad = false;
      if ( ! this.model.get("to").get("iata") ) {
        $("#toInp").parent().addClass("has-error");
        bad = true;
      }
      if (  this.model.get("traveltype") == 'return' && ( 
          ! this.model.get('arrival') || ! $("#arvlDate").val() 
          )
        )  {
        $("#arvlDate").parent().addClass("has-error");
        bad = true;
      }
      if ( bad ) {
        e.stopPropagation();
        return false;
      }

      this.$("button.search").addClass("loading").attr("disabled",true);
      
      var m = this.model;
      $.ajax({
        url: '/start',
        type: 'post',
        data: {
          'traveltype':m.get('traveltype'),
          'departure':this.yyyymmdd(m.get('departure')),
          'arrival':this.yyyymmdd(m.get('arrival')),
          'to'   : m.get("to").get("iata"),
          'from' : m.get("from").get("iata")
        },
        success: function(response) {
          //console.log("response",response);
          window.setTimeout( function() {
            window.location.href = "/result/" + response.id;
          }, 1000 );
          
        }
      });
    },

    onChangeDateDep: function(evt) {
        this.model.set('departure',evt.date,true);
    },

    onChangeDateArvl: function(evt) {
        this.model.set('arrival',evt.date,true);
    }

  });

  return indexApp;
});
