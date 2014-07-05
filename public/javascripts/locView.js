define([
  'jquery',

  'underscore',
  'backbone',
  'menuView',
  'jqueryui'
], function($, _, Backbone,MenuView){
  var LocView  = Backbone.View.extend({
    defaults: {
        infocus: false,
        inacselect: false,
        menuopened: false,
        lastResultLen: 0,
        menuOpened: false,
    },
    events: {
        'blur input[type="text"]': 'onblur',
        'focus input[type="text"]': 'onfocus',
    },
    initialize: function() {
        _.bindAll(this, 'render');
        this.$el = $(this.el);
        this.initUI();
        this.render();
        
        /*
        this.menuView = new MenuView({
            model: this.model,
            $owner: this.placholder,
        });
        */

        this.model.on('change:iata',this.mVal.bind(this));
        //this.model.on('change:selection',this.mValSelection.bind(this));
    },
    initUI: function() {
        var p = this.$el.position();
        this.$input = this.$("input[type=\"text\"]");

        this.placholder = $('<div class="form-control locview">');
        if (this.$input.hasClass("input-lg")) {
          this.placholder.addClass("input-lg");
        }
        // Borderradius fix 
        // otherwise it should be replaced with :last element
        this.$input.css("border-radius",this.$input.css("border-radius"));

        this.placholder.css({
            top: '0px',
            left: this.$input.offset().left - this.$input.parent().offset().left,
            'height':        this.$input.outerHeight() , // display: table comes with border  +2 was used for old chrome 
            'width':         this.$input.outerWidth(),
            //'margin-bottom': this.$el.css("margin-bottom"),
            //'margin-top':    this.$el.css("margin-top")
        });
        
        this.$el.append(this.placholder);

        this.placholder.click(this.onplclick.bind(this));
        this.placholder.hide();

        this.$input.autocomplete({
            minLength: 2,
            source: this.onacsource.bind(this),
            close: this.onacclose.bind(this),
            change: this.onacchange.bind(this),
            select: this.onacselect.bind(this),
            open:  this.onacopen.bind(this)
        });

        var ac = this.$input.data( "ui-autocomplete" );

        ac._renderMenu = function( ul, items ) {
          var that = this;
          var term = this.term.toLowerCase();
          var city_refs = {};
          
          function calcRating(term,item) {
            if ( term.length == 3 && item.iata.toLowerCase() == term )
              return "1";
            if ( item.name.substr(0,term.length).toLowerCase() == term ) 
              return "2";
            return "3";
          }

          for ( var i = 0; i < items.length ; i++) {
            if ( items[i].t == 'city' ) {
              var rating = calcRating(term,items[i]);
              city_refs[items[i].iata]=items[i];
              items[i].rating = rating + ":" + items[i].name + ":" +items[i].iata ;
            }
          }

          for ( var i = 0; i < items.length ; i++) {
            var rating = calcRating(term,items[i]);
            if ( items[i].t == 'airport' ) 
              if ( items[i].city_iata in city_refs ) {
                items[i].rating = city_refs[items[i].city_iata].rating + ":" + rating + ":" + items[i].name + ":" +items[i].iata ;
                items[i]._subAirport = true;
              }
              else 
                items[i].rating = rating + ":" + items[i].name + ":" +items[i].iata ;
          }

          items = items.sort(function(x,y) { 
            if ( x.rating > y.rating ) return 1;
            if ( x.rating < y.rating ) return -1;
            return 0;
          } );

          console.log(_.pluck(items,'name'));
          console.log(_.pluck(items,'rating'));

          $.each( items, function( index, item ) {
            that._renderItemData( ul, item );
          });
        };

        ac._renderItem = function( ul, item ) {
            var flag   = $('<i class="flag"></span>').addClass(item.country_code.toLowerCase());
            var a =  $("<a>");
            
            a.append(
              $('<span class="iata"></span>').html(item.iata)
            );

            if ( item.t == 'airport') {
              if (item._subAirport ) {
                a.addClass("sub-airport") 
                a.append(
                  $('<span class="desc"></span>').html(item.name)
                );
              } else {
                a.append(flag);
                a.append(
                  $('<span class="city"></span>').html(item.city)
                );
                if ( item.name != item.city)
                  a.append(
                    $('<span class="desc"></span>').html(item.name)
                  );
              }
            } else {
              a.append(flag);
              a.append(
                $('<span class="city"></span>').html(item.name)
              );
            }
            
          

            var el = $( "<li>" ).addClass("f16").append( a );
            ul.append(el);
            return el;

        };
    },

    render: function(){
        this.rebuild();
      /*$(this.el).append("<ul> <li>hello world</li> </ul>");*/
    },
    rebuild: function() {
        this.mVal();
        //this.mValSelection();
    },

    mVal: function() {
        var name = this.model.get('name');
        if ( ! name ) {
          this.placholder.empty();
          this.placholder.hide();
          this.$input.val("");
        } else {
          var aidx =  $('<span class="air-idx"></span>').html(this.model.get('iata'));
          var atitle = $('<span class="air-title"></span>').html(name);
          
          this.placholder.empty();
          this.placholder.append(atitle);    
          this.placholder.append(aidx);
          
          this.$input.val(name);

          this.placholder.show();
        } 
        
    },
    onblur: function() {
      if ( this.model.get("name") )
        this.placholder.show();
      this.infocus = false;
    },

    onplclick: function() {
      this.$input.focus();
    },
    onfocus: function() {
      this.$input.val(this.model.get('name'));

      this.model.set('selection',true)
      this.infocus = true;
      this.placholder.hide();
    },
    onacsource: function(q,cb) {
        var self = this;
        $.ajax({
            url: '/term',
            type: 'get',
            data: {
                'q': q.term
            },
            success: function(response) {
              var items = [];
              self.lastResultLen = response.result.length;
              for ( var i = 0 ;i < response.result.length ;i++) {
                  var item = response.result[i];
                  // value shown on input
                  item.value = item.name;

                  items.push(item);
              }
              cb(items);
            },
            error: function() {
                self.lastResultLen = 0;
                cb([]);
            }
        });
    },
    onacclose: function() {
        this.menuopened = false;
        if (! this.itemselected ) {
            
        }
    },
    onacopen: function() {
        this.menuopened = true;
        this.itemselected = false;
    },
    onacselect:function( event, ui ) {
      this.model.set(ui.item);
      this.itemselected = true;
      this.$input.blur();
      return false;
    },
    onacchange: function() {
      if ( this.itemselected) return;
      if ( this.$input.val() == "") {
          this.model.clear();
          return;
      }

      if ( this.lastResultLen ) {
          var menu =this.$input.data( "uiAutocomplete" ).menu;
          var data = menu.element.children().first().data('uiAutocompleteItem');
          this.model.set(data);        
      }
    }

  });
  return LocView;
});
