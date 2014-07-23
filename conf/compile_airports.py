import json
import csv, codecs, cStringIO
import re

class UTF8Recoder:
    """
    Iterator that reads an encoded stream and reencodes the input to UTF-8
    """
    def __init__(self, f, encoding):
        self.reader = codecs.getreader(encoding)(f)

    def __iter__(self):
        return self

    def next(self):
        return self.reader.next().encode("utf-8")

class UnicodeReader:
    """
    A CSV reader which will iterate over lines in the CSV file "f",
    which is encoded in the given encoding.
    """

    def __init__(self, f, dialect=csv.excel, encoding="utf-8", **kwds):
        f = UTF8Recoder(f, encoding)
        self.reader = csv.reader(f, dialect=dialect, **kwds)

    def next(self):
        row = self.reader.next()
        return [unicode(s, "utf-8") for s in row]

    def __iter__(self):
        return self

class UnicodeWriter:
    """
    A CSV writer which will write rows to CSV file "f",
    which is encoded in the given encoding.
    """

    def __init__(self, f, dialect=csv.excel, encoding="utf-8", **kwds):
        # Redirect output to a queue
        self.queue = cStringIO.StringIO()
        self.writer = csv.writer(self.queue, dialect=dialect, **kwds)
        self.stream = f
        self.encoder = codecs.getincrementalencoder(encoding)()

    def writerow(self, row):
        self.writer.writerow([s.encode("utf-8") for s in row])
        # Fetch UTF-8 output from the queue ...
        data = self.queue.getvalue()
        data = data.decode("utf-8")
        # ... and reencode it into the target encoding
        data = self.encoder.encode(data)
        # write to the target stream
        self.stream.write(data)
        # empty queue
        self.queue.truncate(0)

    def writerows(self, rows):
        for row in rows:
            self.writerow(row)


jsond = json.load(open("_cities.json"))

csvd = []

def dp(c):
	d = airports[c]
	print "--------------"
	print "Code:" + c
	print "Type:" + d['otype']
	print "city_name"
	print "en:" + (d['city_name_en'] or '***')
	print "de:" + (d['city_name_de'] or '***')
	print "ru:" + (d['city_name_ru'] or '***')
	print "airport_name"
	print "en:" + (d['airport_name_en'] or '***')
	print "de:" + (d['airport_name_de'] or '***')
	print "ru:" + (d['airport_name_ru'] or '***')
	print "--------------"

airports={}
cities={}
fikos={}
iso_codes = {'de':{},'ru':{},'en':{}}

for c in ('de','ru','en'):
	with open('_iso_codes_'+c+'.txt') as csvfile:
		r = UnicodeReader(csvfile, delimiter=' ', quotechar='"',skipinitialspace=True)
		for i in r:
			iso_codes[c][i[0]]=" ".join(i[1:])

with open('_airports_fiko_ru.txt') as csvfile:
	r = UnicodeReader(csvfile, delimiter='\t', quotechar='"',skipinitialspace=True)
	r.next()
	for i in r:
		if i[0]:
			(lat,lon) = i[4].split(",")
			lon = float(re.sub("[^0-9.-]","",lon))
			lat = float(re.sub("[^0-9.-]","",lat))

			fikos[i[0]]={
				'airport_name_ru':i[1],
				'city_name_ru':i[2],
				'lon':lon,
				'lat':lat,
				'country_code':i[3]
			}

with open('_airports.csv', 'rb') as csvfile:
	r = UnicodeReader(csvfile, delimiter=';', quotechar='"',skipinitialspace=True)
	r.next()
	# ['0A7;"city";"Hendersonville";"35.3187279', '-82.4609528";;"United States"']
	for i in r:
		lon = 0.0
		lat = 0.0
		iata  = i[0]
		if i[3]:
			(lat,lon) = i[3].split(":")
			lon = float(re.sub("[^0-9.-]","",lon))
			lat = float(re.sub("[^0-9.-]","",lat))
		elif iata in fikos:
			lon = fikos[iata]['lon']
			lat = fikos[iata]['lat']

		if lon != 0.0 and lat != 0.0:
			otype = i[1]
			
			airport_name = i[2] if otype == 'airport' else None
			city_name  =  i[2] if otype == 'city' else i[5] 
			country_name  = i[5] if otype == 'city' else None
			city_iata  = iata if otype == 'city' else None

			m = cities if otype == 'city' else airports
			
			if airport_name and 'hauptbahnhof' in airport_name.lower() :
				continue

			m[iata] = {
				'otype': otype,
				'city_iata': city_iata,
				'airport_name_en': airport_name,
				'city_name_en': city_name,
				'country_name_en':country_name,

				'airport_name_de': None,
				'city_name_de': None,
				'country_name_de':None,
				
				'airport_name_ru': None,
				'city_name_ru': None,
				'country_name_ru':None,

				'country_code':None,
				'timezone':i[4],
				'lon':	lon,
				'lat':	lat,
				'airports_count' : 0
			}


for i in jsond:
	for m in (airports,cities):
		if i['code'] in m:
			otype = m[i['code']]['otype']
			#if otype == 'city':
			m[i['code']]['city_name_ru'] = i['name_translations'].get('ru',None)
			m[i['code']]['city_name_de'] = i['name_translations'].get('de',None)
			m[i['code']]['country_code'] =  i['country_code']

for code in fikos:
	for m in (airports,cities):
		if code in m:
			otype = m[code]['otype']
			if otype == 'airport':
				m[code]['airport_name_ru'] = fikos[code]['airport_name_ru']
			else:
				if m[code]['city_name_ru']:
					if m[code]['city_name_ru'] != fikos[code]['city_name_ru']:
						if fikos[code]['city_name_ru']:
							m[code]['city_name_ru'],fikos[code]['city_name_ru']
				else:
					m[code]['city_name_ru'] = fikos[code]['city_name_ru']

cities_ref = {}

for x in cities:
	cname = cities[x]['city_name_en']
	if cname in cities_ref :
		cities_ref[cname].append(cities[x])
	else:
		cities_ref[cname] = [cities[x],]

## Blacklist
blacklist = set(['ALV','WID','MGL','GKE','NGU','NIP','GMY'])
for _ in blacklist:
	if _ in airports:
		del airports[_]
	if _ in cities:
		del cities[_]

unk_ap=[]

for c in airports:
	x = airports[c]
	if x['city_name_en'] in cities_ref:
		items = cities_ref[x['city_name_en']]
		if len(items) > 1:
			if c in set(['BMA','VST','NYO']):
				item = [i for i in items if i['city_iata'] == 'STO'][0]
			else:
				v0 = [i for i in items if i['city_iata'] == c]
				if len(v0) == 1:
					item = v0[0]
				else:
					v1 = [i for i in items if i['timezone'] == x['timezone']]
					if len(v1) == 1:
						item = v1[0]
					else:
						if c in fikos:
							v1 = [i for i in items if i['country_code'] == fikos[c]['country_code'].upper() ]
						if len(v1) == 1:
							item = v1[0]
						else:
							print "Ambigous CITY"
							print "AIRPORT",c,x
							print "VARIANTS",items
							1/0
		else:
			item  = items[0]
		x['country_code']    = item['country_code']
		x['country_name_en'] = item['country_name_en']
		x['city_name_ru'] = item['city_name_ru']
		x['city_name_de'] = item['city_name_de']
		x['city_iata'] = item['city_iata']
		item['airports_count']+=1

		#if not x['city_name_ru']:
		#	print c,x
		#	0/0

country_refs = {}
for m in (airports,cities):
	for iata in m:
		d = m[iata]
		if d['country_code']:
			country_refs[d['country_name_en']]=d['country_code']

for m in (airports,cities):
	for iata in m:
		d = m[iata]
		if d['country_name_en']:
			d['country_code'] = country_refs[d['country_name_en']]

for c in ('de','ru',):
	for m in (airports,cities):
		for iata in m:
			d = m[iata]
			if d['country_code'] in iso_codes[c]:
				d['country_name_'+c] = iso_codes[c][d['country_code']]

for m in (airports,cities):
	for iata in m:
		d = m[iata]
		if d['country_code'] in iso_codes['en'] and not d['country_name_en']:
			d['country_name_en'] = iso_codes['en'][d['country_code']]

ret = []

for m in (airports,cities):
	for iata in m:
		d = m[iata]
		d['iata'] = iata
		
		d['lon'] = unicode(d['lon'])
		d['lat'] = unicode(d['lat'])
		d['airports_count'] = unicode(d['airports_count'])

		ret.append(d)

print "DB Fetched",len(ret)

ret = [r for r in ret if not ( r['airports_count'] == '1' and r['otype'] == 'city' )]

print "DB Filtered",len(ret)

ret2=[]
for r in ret:
	if r['airports_count'] == '0' and r['otype'] == 'city' :
		if any([r['iata']==_['iata'] and _['otype'] == 'airport' for _ in ret]):
			continue

		r['otype'] = 'airport'
		r['airport_name_en'] = r['city_name_en']
		r['airport_name_de'] = r['city_name_de']
		r['airport_name_ru'] = r['city_name_ru']

	ret2.append(r)

ret = ret2

print "DB Filtered2",len(ret)


# # TODO: REMOVE FILTERS AND MAKE FIX data
# ret = [r for r in ret if not ( r['country_name_en'] is None)]

print "removed bad country",len(ret)

keys = ['iata','otype','city_iata',
	'timezone','lon','lat',
	
	'airport_name_en','city_name_en','country_name_en',
	'airport_name_de','city_name_de','country_name_de',
	'airport_name_ru','city_name_ru','country_name_ru',

	'country_code','airports_count'
]

with open('airports.csv', 'wb') as csvfile:
	spamwriter = UnicodeWriter(csvfile, delimiter=',',quotechar='"', quoting=csv.QUOTE_MINIMAL)
	spamwriter.writerow(keys)
	for r in ret:
		spamwriter.writerow([r[k] or u'' for k in keys])


# Some Test

without_country_code = [_ for _ in ret if not _['country_code']]

print "Vals without contry code: %d" % len(without_country_code)
if len(without_country_code): 
	print "For example %s" % without_country_code[0]


x = [_ for _ in ret if not _['city_name_ru']]
print "Vals without City Name RU: %d" % len(x)
if len(x): 
	print "For example %s" % x[0]

x = [_ for _ in ret if not _['country_name_de']]
print "Vals without Country Name DE: %d" % len(x)
if len(x): 
	print "For example %s" % x[1]

x = [_ for _ in ret if not _['country_name_ru']]
print "Vals without Country Name RU: %d" % len(x)
if len(x): 
	print "For example %s" % x[1]

x = [_ for _ in ret if not _['country_name_en']]
print "Vals without Country Name EN: %d" % len(x)
if len(x): 
	print "For example %s" % x[0]

x = [_ for _ in ret if not _['city_iata'] and _['otype']=='airport']
print "Vals without city_iata: %d" % len(x)
if len(x): 
	print "For example %s" % x[0]


for d in ret:
	if d['otype']=='airport':
		for l in ('ru','de'):
			 if not d["airport_name_"+l]:
			 	if d['airport_name_en'] == d['city_name_en'] and d['city_name_'+l]:
			 		d["airport_name_"+l]=d['city_name_'+l]

for d in ret:
	for l in ('ru','de'):
		for k in ('city_name','country_name','airport_name'):
			if not d[k+"_"+l]:
				d[k+"_"+l] = d[k+"_en"]


for d in ret:
	d['lon'] = float(d['lon'])
	d['lat'] = float(d['lat'])


json.dump(ret,open("airports.json","w"))

#for i in jsond:
#	if i['code'] in airports:
#		print airports[i['code']]['name_en'],i['name_translations']['ru']
#		break
		#if i['code'] in cities:
		#	print i['code'],i['name'],'CITY'
#print next(i for i in jsond if 'stuttgart' in i['name'].lower())
#print csvd