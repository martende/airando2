require.config({
    shim: {
        underscore: {
            exports: '_'
        },
        datepicker: {
            deps: ['jqueryui']
        },
        slider: {
            deps: ['jqueryui']
        },
        jqueryui: {
            deps: ['jquery']
        }
    },
    paths: {
        jquery: 'jquery-1.9.0.min',
        jqueryui: 'vendor/jquery-ui',
        underscore: 'http://cdnjs.cloudflare.com/ajax/libs/underscore.js/1.5.2/underscore',
        backbone: 'http://backbonejs.org/backbone',
        datepicker: 'vendor/bootstrap-datepicker',
        slider: 'vendor/bootstrap-slider',

        //moment: "vendor/moment-with-langs.min"
        moment: "http://momentjs.com/downloads/moment-with-langs",
        slick:  "http://cdn.jsdelivr.net/jquery.slick/1.3.6/slick.min"
        //slick:  "https://raw.githubusercontent.com/kenwheeler/slick/master/slick/slick"
        //pixi: 'vendor/pixi.dev',
        //kinetic: 'vendor/kinetic-v5.1.0'
    }
});

require(["router","moment"],function(Router,moment) {
    console.log("main.js", $.ui);

    moment.lang(window.initData.lang);
	Router.initialize();
});
