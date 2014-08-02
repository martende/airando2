var page = require('webpage').create();
page.open('http://www.airberlin.com/de-DE/site/start.php', function() {
  var title = page.evaluate(function() {
    return document.title;
  });
  console.log(title);
  page.render('example.png');
  phantom.exit();
});
