define([
  'underscore',
  'backbone',
  'menuView'
], function( _, Backbone,MenuView){
  var MenuView = Backbone.View.extend({
      defaults: {
          menuOpened: false,
          loading: false,
          $owner: null
      },
      initialize: function(options) {
          this.$owner = options.$owner;

          this.$el = $("<div>");
          this.el = this.$el[0];

          this.$el.appendTo( document.body ),
              
          
          
          this.$el.hide();
          this.$el.addClass("dropdown").addClass("open").appendTo(document.body);
          this.$ul = $( "<ul>" ).addClass( "dropdown-menu" ).addClass("f16").css({"role":"menu"});
          this.$ul.appendTo(this.$el);
      },
      open: function() {
          var self = this;
          if ( this.menuOpened ) return;
          this.setupMenu();
          this.$el.show();
          this.loading=true;
          $.ajax({
              url: '/nearest',
              type: 'get',
              data: {
                  'code': this.model.getCodes().join(",")
              },
              success: function(response) {

                  var r = response.result;
                  var usdCodes = self.model.getCodes();
                  for (var i=0;i<response.result.length;i++) {
                      var chkd ="";
                      if (self.model.hasCode(r[i].code) ) chkd = " checked";
                      var t = '<a><input type="checkbox"'+chkd+"> "+
                          '<i class="flag '+r[i].country.toLowerCase()+'"></i>' +
                          '<span class="code">'+r[i].code+ '</span> '+
                          '<span class="name">'+r[i].city+ '</span> <span class="distance">' + r[i].distance +" km</span></a>";
                      var $li = $( "<li>" ).html(t).appendTo( self.$ul );    
                      $li.data("airp",r[i]);
                  }
                  $(".waitmask",self.$ul).remove();
                  self.loading = false;
              },
              error: function() {
                  //self.lastResultLen = 0;
                  //cb([]);
                  self.hide();
                  self.loading = false;
              }
          });

          this.menuOpened=true;
      },
      hide: function() {
          if ( this.menuOpened ) {
              this.menuOpened = false;
              this.$el.hide();
              $(document).off('click', this.outerClickHandler);
              this.outerClickHandler = null;
          }
      },
      onClick: function(e) {
          if ( ! $.contains(this.el,e.target)) {
              this.hide();
              return;    
          }
          var isCheckbox = (e.target.tagName == "INPUT");
          var $li = $(e.target).closest("li");
          var code = $li.data("code");
          var $cb = $("input",$li);
          if ( ! isCheckbox ) {
              if ( $cb.is(":checked") ) {
                  $cb.prop("checked",false);
                  this.model.removeAirport($li.data("airp")['code']);
              } else {
                  $cb.prop("checked",true);
                  this.model.addAirport($li.data("airp"));
              }    
          } else {
              if ( $cb.is(":checked") ) {
                  this.model.addAirport($li.data("airp"));
              } else {
                  this.model.removeAirport($li.data("airp")['code']);
              }
          }
          
      },
      setupMenu: function() {

          this.$ul.empty();
          var of = this.$owner.offset();
          this.$el.css({
              /*height:this.$owner.outerHeight(), */
              width: this.$owner.outerWidth(),
              position:'absolute',
              top: of.top + this.$owner.outerHeight(),
              left: of.left
          });
          this.$ul.css({
              top: "0px",
              width: this.$owner.outerWidth()
          });

          this.outerClickHandler = this.onClick.bind(this);

          $(document).on('click', this.outerClickHandler);

          var alist = "<b>" + this.model.getCities().join("</b>, <b>") + "</b>";

          $( "<li>" ).html( "Airports near: " + alist).addClass("header")
          .appendTo( this.$ul );

          $( "<li>" ).addClass( "divider")
          .appendTo( this.$ul );

          $( "<li>" ).addClass("waitmask").html('<div class="wait-icon"></div>')
          .appendTo( this.$ul );

      }
  });
  return MenuView;
});