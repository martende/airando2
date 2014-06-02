require.config({
    shim: {
        underscore: {
            exports: '_'
        },
    },
    paths: {
        jquery: 'jquery-1.9.0.min',
        underscore: 'http://cdnjs.cloudflare.com/ajax/libs/underscore.js/1.5.2/underscore',
        backbone: 'http://backbonejs.org/backbone',
        //pixi: 'vendor/pixi.dev',
        //kinetic: 'vendor/kinetic-v5.1.0'
    }
});

require(["router"],function(Router) {
	Router.initialize();
});
