_cities.json http://api.travelpayouts.com/data/cities.json
_airports.csv https://www.travelpayouts.com/files/airports.zip/airports.csv
_airports_fiko_ru.txt Yandex parsed database mysql -e 'select * from airport where code ="MSQ" limit 10;' fiko > _airports_fiko_ru.txt
_iso_codes_de.txt   From http://www.addressdoctor.com/de/en/laender-daten/iso-laendercodes.html
_iso_codes_ru.txt http://www.exlab.net/tools/tables/regions.html
_timezones.txt    WIKI timezones list from http://en.wikipedia.org/w/index.php?title=List_of_tz_database_time_zones&action=edit&section=1
_airlines.csv	  Airline IATA codes - full of bullshit. https://www.travelpayouts.com/files/airlines.zip

airports.json - Compiled by compile_airports.py database
