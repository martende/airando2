var page = require('webpage').create();
page.onConsoleMessage = function(msg) {
    console.log(msg);
};
page.open('file:///home/belka/airando2/phantomjs/tests/t4.html', function() {
  var title = page.evaluate(function() {

  				var td = document.createElement("div");
			td.style = "position:absolute;top:100px;left:100px;width:100px;height:100px;background-color:green";
			document.body.appendChild(td);

    return document.title;
  });
  console.log("TITLE="+title);
	
  page.sendEvent('mousemove', 150, 150);				
  page.render('../fetchers/images/example1.png');
  
  phantom.exit();
});
