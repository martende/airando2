define([

],function() {
	var d = {
		'ru' : {
			'Refresh' : 'Обновить',
			'Buy':'Купить',
			'on the way': 'в пути',
			'Via' : 'Через',
			'stop lasts' : 'остановка длится'
		},
		'en' : {

		},
		'de' : {
			'Refresh' : 'Neu laden',
			'Buy':'Kaufen',
			'on the way' : 'auf dem Weg',
			'Via' : 'Dürch',
			'stop lasts' : 'Stopp dauert'
		},
	}
	var f = function(msg) {
		return d[window.initData.lang][msg] ||  msg;
	}
	return f;
})