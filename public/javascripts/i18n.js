define([

],function() {
	var d = {
		'ru' : {
			'Refresh' : 'Обновить',
			'Buy':'Купить'
		},
		'en' : {

		},
		'de' : {
			'Refresh' : 'Neu laden',
			'Buy':'Kaufen'
		},
	}
	var f = function(msg) {
		return d[window.initData.lang][msg] ||  msg;
	}
	return f;
})