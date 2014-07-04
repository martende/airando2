
function betterTypeOf(input) {
    "use strict";
    switch (input) {
        case undefined:
            return 'undefined';
        case null:
            return 'null';
        default:
        try {
            var type = Object.prototype.toString.call(input).match(/^\[object\s(.*)\]$/)[1].toLowerCase();
            if (type === 'object' &&
                phantom.casperEngine !== "phantomjs" &&
                '__type' in input) {
                type = input.__type;
            }
            // gecko returns window instead of domwindow
            else if (type === 'window') {
                return 'domwindow';
            }
            return type;
        } catch (e) {
            return typeof input;
        }
    }
}
exports.betterTypeOf = betterTypeOf;

/**
 * Provides a better instanceof operator, capable of checking against the full object prototype hierarchy.
 *
 * @param  mixed  input
 * @param  function constructor
 * @return String
 * @see    https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Details_of_the_Object_Model
 */
function betterInstanceOf(input, constructor) {
    "use strict";
    /*jshint eqnull:true, eqeqeq:false */
    if (typeof input == 'undefined' || input == null) {
      return false;
    }
    var inputToTest = input;
    while (inputToTest != null) {
      if (inputToTest == constructor.prototype) {
        return true;
      }
      if (typeof inputToTest == 'xml') {
        return constructor.prototype == document.prototype;
      }
      if (typeof inputToTest == 'undefined') {
        return false;
      }
      inputToTest = inputToTest.__proto__;
    }
    return equals(input.constructor.name, constructor.name);
}
exports.betterInstanceOf = betterInstanceOf;

/**
 * Cleans a passed URL.
 *
 * @param  String  url An HTTP URL
 * @return String
 */
function cleanUrl(url) {
    "use strict";
    if (url.toLowerCase().indexOf('http') !== 0) {
        return url;
    }
    var a = document.createElement('a');
    a.href = url;
    return a.href;
}
exports.cleanUrl = cleanUrl;

/**
 * Clones an object.
 *
 * @param  Mixed  o
 * @return Mixed
 */
function clone(o) {
    "use strict";
    return JSON.parse(JSON.stringify(o));
}
exports.clone = clone;

/**
 * Computes a modifier string to its PhantomJS equivalent. A modifier string is
 * in the form "ctrl+alt+shift".
 *
 * @param  String  modifierString  Modifier string, eg. "ctrl+alt+shift"
 * @param  Object  modifiers       Modifiers definitions
 * @return Number
 */
function computeModifier(modifierString, modifiers) {
    "use strict";
    var modifier = 0,
        checkKey = function(key) {
            if (key in modifiers) return;
            throw new CasperError(format('%s is not a supported key modifier', key));
        };
    if (!modifierString) return modifier;
    var keys = modifierString.split('+');
    keys.forEach(checkKey);
    return keys.reduce(function(acc, key) {
        return acc | modifiers[key];
    }, modifier);
}
exports.computeModifier = computeModifier;

/**
 * Decodes a URL.
 * @param  String  url
 * @return String
 */
function decodeUrl(url) {
    "use strict";
    try {
        return decodeURIComponent(url);
    } catch (e) {
        /*global unescape*/
        return unescape(url);
    }
}
exports.decodeUrl = decodeUrl;

/**
 * Dumps a JSON representation of passed value to the console. Used for
 * debugging purpose only.
 *
 * @param  Mixed  value
 */
function dump(value) {
    "use strict";
    console.log(serialize(value, 4));
}
exports.dump = dump;

/**
 * Tests equality between the two passed arguments.
 *
 * @param  Mixed  v1
 * @param  Mixed  v2
 * @param  Boolean
 */
function equals(v1, v2) {
    "use strict";
    if (isFunction(v1)) {
        return v1.toString() === v2.toString();
    }
    // with Gecko, instanceof is not enough to test object
    if (v1 instanceof Object || isObject(v1)) {
        if (!(v2 instanceof Object || isObject(v2)) ||
            Object.keys(v1).length !== Object.keys(v2).length) {
            return false;
        }
        for (var k in v1) {
            if (!equals(v1[k], v2[k])) {
                return false;
            }
        }
        return true;
    }
    return v1 === v2;
}
exports.equals = equals;

/**
 * Returns the file extension in lower case.
 *
 * @param  String  file  File path
 * @return string
 */
function fileExt(file) {
    "use strict";
    try {
        return file.split('.').pop().toLowerCase().trim();
    } catch(e) {
        return '';
    }
}
exports.fileExt = fileExt;

/**
 * Takes a string and append blanks until the pad value is reached.
 *
 * @param  String  text
 * @param  Number  pad   Pad value (optional; default: 80)
 * @return String
 */
function fillBlanks(text, pad) {
    "use strict";
    pad = pad || 80;
    if (text.length < pad) {
        text += new Array(pad - text.length + 1).join(' ');
    }
    return text;
}
exports.fillBlanks = fillBlanks;

/**
 * Formats a string with passed parameters. Ported from nodejs `util.format()`.
 *
 * @return String
 */
function format(f) {
    "use strict";
    var i = 1;
    var args = arguments;
    var len = args.length;
    var str = String(f).replace(/%[sdj%]/g, function _replace(x) {
        if (i >= len) return x;
        switch (x) {
        case '%s':
            return String(args[i++]);
        case '%d':
            return Number(args[i++]);
        case '%j':
            return JSON.stringify(args[i++]);
        case '%%':
            return '%';
        default:
            return x;
        }
    });
    for (var x = args[i]; i < len; x = args[++i]) {
        if (x === null || typeof x !== 'object') {
            str += ' ' + x;
        } else {
            str += '[obj]';
        }
    }
    return str;
}
exports.format = format;

/**
 * Formats a test value.
 *
 * @param  Mixed  value
 * @return String
 */
function formatTestValue(value, name) {
    "use strict";
    var formatted = '';
    if (value instanceof Error) {
        formatted += value.message + '\n';
        if (value.stack) {
            formatted += indent(value.stack, 12, '#');
        }
    } else if (name === 'stack') {
        if (isArray(value)) {
            formatted += value.map(function(entry) {
                return format('in %s() in %s:%d', (entry['function'] || "anonymous"), entry.file, entry.line);
            }).join('\n');
        } else {
            formatted += 'not provided';
        }
    } else {
        try {
            formatted += serialize(value);
        } catch (e) {
            try {
                formatted += serialize(value.toString());
            } catch (e2) {
                formatted += '(unserializable value)';
            }
        }
    }
    return formatted;
}
exports.formatTestValue = formatTestValue;

/**
 * Retrieves the value of an Object foreign property using a dot-separated
 * path string.
 *
 * Beware, this function doesn't handle object key names containing a dot.
 *
 * @param  Object  obj   The source object
 * @param  String  path  Dot separated path, eg. "x.y.z"
 */
function getPropertyPath(obj, path) {
    "use strict";
    if (!isObject(obj) || !isString(path)) {
        return undefined;
    }
    var value = obj;
    path.split('.').forEach(function(property) {
        if (typeof value === "object" && property in value) {
            value = value[property];
        } else {
            value = undefined;
        }
    });
    return value;
}
exports.getPropertyPath = getPropertyPath;

/**
 * Indents a string.
 *
 * @param  String  string
 * @param  Number  nchars
 * @param  String  prefix
 * @return String
 */
function indent(string, nchars, prefix) {
    "use strict";
    return string.split('\n').map(function(line) {
        return (prefix || '') + new Array(nchars).join(' ') + line;
    }).join('\n');
}
exports.indent = indent;

/**
 * Inherit the prototype methods from one constructor into another.
 *
 * @param {function} ctor Constructor function which needs to inherit the
 *     prototype.
 * @param {function} superCtor Constructor function to inherit prototype from.
 */
function inherits(ctor, superCtor) {
    "use strict";
    ctor.super_ = ctor.__super__ = superCtor;
    ctor.prototype = Object.create(superCtor.prototype, {
        constructor: {
            value: ctor,
            enumerable: false,
            writable: true,
            configurable: true
        }
    });
}
exports.inherits = inherits;

function isArray(value) {
    "use strict";
    return Array.isArray(value) || isType(value, "array");
}
exports.isArray = isArray;

/**
 * Checks if value is a phantomjs clipRect-compatible object
 *
 * @param  mixed  value
 * @return Boolean
 */
function isClipRect(value) {
    "use strict";
    return isType(value, "cliprect") || (
        isObject(value) &&
        isNumber(value.top) && isNumber(value.left) &&
        isNumber(value.width) && isNumber(value.height)
    );
}
exports.isClipRect = isClipRect;

/**
 * Checks that the subject is falsy.
 *
 * @param  Mixed  subject  Test subject
 * @return Boolean
 */
function isFalsy(subject) {
    "use strict";
    /*jshint eqeqeq:false*/
    return !subject;
}
exports.isFalsy = isFalsy;
/**
 * Checks if value is a javascript Function
 *
 * @param  mixed  value
 * @return Boolean
 */
function isFunction(value) {
    "use strict";
    return isType(value, "function");
}
exports.isFunction = isFunction;

/**
 * Checks if passed resource involves an HTTP url.
 *
 * @param  Object  resource The PhantomJS HTTP resource object
 * @return Boolean
 */
function isHTTPResource(resource) {
    "use strict";
    return isObject(resource) && /^http/i.test(resource.url);
}
exports.isHTTPResource = isHTTPResource;

/**
 * Checks if a file is apparently javascript compatible (.js or .coffee).
 *
 * @param  String  file  Path to the file to test
 * @return Boolean
 */
function isJsFile(file) {
    "use strict";
    var ext = fileExt(file);
    return isString(ext, "string") && ['js', 'coffee'].indexOf(ext) !== -1;
}
exports.isJsFile = isJsFile;

/**
 * Checks if the provided value is null
 *
 * @return Boolean
 */
function isNull(value) {
    "use strict";
    return isType(value, "null");
}
exports.isNull = isNull;

/**
 * Checks if value is a javascript Number
 *
 * @param  mixed  value
 * @return Boolean
 */
function isNumber(value) {
    "use strict";
    return isType(value, "number");
}
exports.isNumber = isNumber;

/**
 * Checks if value is a javascript Object
 *
 * @param  mixed  value
 * @return Boolean
 */
function isObject(value) {
    "use strict";
    var objectTypes = ["array", "object", "qtruntimeobject"];
    return objectTypes.indexOf(betterTypeOf(value)) >= 0;
}
exports.isObject = isObject;

/**
 * Checks if value is a RegExp
 *
 * @param  mixed  value
 * @return Boolean
 */
function isRegExp(value) {
    "use strict";
    return isType(value, "regexp");
}
exports.isRegExp = isRegExp;

/**
 * Checks if value is a javascript String
 *
 * @param  mixed  value
 * @return Boolean
 */
function isString(value) {
    "use strict";
    return isType(value, "string");
}
exports.isString = isString;

/**
 * Checks that the subject is truthy.
 *
 * @param  Mixed  subject  Test subject
 * @return Boolean
 */
function isTruthy(subject) {
    "use strict";
    /*jshint eqeqeq:false*/
    return !!subject;
}
exports.isTruthy = isTruthy;

/**
 * Shorthands for checking if a value is of the given type. Can check for
 * arrays.
 *
 * @param  mixed   what      The value to check
 * @param  String  typeName  The type name ("string", "number", "function", etc.)
 * @return Boolean
 */
function isType(what, typeName) {
    "use strict";
    if (typeof typeName !== "string" || !typeName) {
        throw new CasperError("You must pass isType() a typeName string");
    }
    return betterTypeOf(what).toLowerCase() === typeName.toLowerCase();
}
exports.isType = isType;

/**
 * Checks if the provided value is undefined
 *
 * @return Boolean
 */
function isUndefined(value) {
    "use strict";
    return isType(value, "undefined");
}
exports.isUndefined = isUndefined;

/**
 * Checks if value is a valid selector Object.
 *
 * @param  mixed  value
 * @return Boolean
 */
function isValidSelector(value) {
    "use strict";
    if (isString(value)) {
        try {
            // phantomjs env has a working document object, let's use it
            document.querySelector(value);
        } catch(e) {
            if ('name' in e && (e.name === 'SYNTAX_ERR' || e.name === 'SyntaxError')) {
                return false;
            }
        }
        return true;
    } else if (isObject(value)) {
        if (!value.hasOwnProperty('type')) {
            return false;
        }
        if (!value.hasOwnProperty('path')) {
            return false;
        }
        if (['css', 'xpath'].indexOf(value.type) === -1) {
            return false;
        }
        return true;
    }
    return false;
}
exports.isValidSelector = isValidSelector;

/**
 * Checks if the provided var is a WebPage instance
 *
 * @param  mixed  what
 * @return Boolean
 */
function isWebPage(what) {
    "use strict";
    return betterTypeOf(what) === "qtruntimeobject" && what.objectName === 'WebPage';
}
exports.isWebPage = isWebPage;



function isPlainObject(obj) {
    "use strict";
    if (!obj || typeof(obj) !== 'object')
        return false;
    var type = Object.prototype.toString.call(obj).match(/^\[object\s(.*)\]$/)[1].toLowerCase();
    return (type === 'object');
}

/**
 * Object recursive merging utility for use in the SlimerJS environment
 *
 * @param  Object  origin  the origin object
 * @param  Object  add     the object to merge data into origin
 * @param  Object  opts    optional options to be passed in
 * @return Object
 */
function mergeObjectsInGecko(origin, add, opts) {
    "use strict";

    var options = opts || {},
        keepReferences = options.keepReferences;

    for (var p in add) {
        if (isPlainObject(add[p])) {
            if (isPlainObject(origin[p])) {
                origin[p] = mergeObjects(origin[p], add[p]);
            } else {
                origin[p] = keepReferences ? add[p] : clone(add[p]);
            }
        } else {
            // if a property is only a getter, we could have a Javascript error
            // in strict mode "TypeError: setting a property that has only a getter"
            // when setting the value to the new object (gecko 25+).
            // To avoid it, let's define the property on the new object, do not set
            // directly the value
            var prop = Object.getOwnPropertyDescriptor(add, p);
            if (prop.get && !prop.set) {
                Object.defineProperty(origin, p, prop)
            }
            else {
                origin[p] = add[p];
            }
        }
    }
    return origin;
}

/**
 * Object recursive merging utility.
 *
 * @param  Object  origin  the origin object
 * @param  Object  add     the object to merge data into origin
 * @param  Object  opts    optional options to be passed in
 * @return Object
 */
function mergeObjects(origin, add, opts) {
    "use strict";

    var options = opts || {},
        keepReferences = options.keepReferences;

    if (phantom.casperEngine === 'slimerjs') {
        // Because of an issue in the module system of slimerjs (security membranes?)
        // constructor is undefined.
        // let's use an other algorithm
        return mergeObjectsInGecko(origin, add, options);
    }

    for (var p in add) {
        if (add[p] && add[p].constructor === Object) {
            if (origin[p] && origin[p].constructor === Object) {
                origin[p] = mergeObjects(origin[p], add[p]);
            } else {
                origin[p] = keepReferences ? add[p] : clone(add[p]);
            }
        } else {
            origin[p] = add[p];
        }
    }
    return origin;
}
exports.mergeObjects = mergeObjects;

/**
 * Converts milliseconds to seconds and rounds the results to 3 digits accuracy.
 *
 * @param  Number  milliseconds
 * @return Number  seconds
 */
function ms2seconds(milliseconds) {
    "use strict";
    return Math.round(milliseconds / 1000 * 1000) / 1000;
}
exports.ms2seconds = ms2seconds;

/**
 * Creates an (SG|X)ML node element.
 *
 * @param  String  name        The node name
 * @param  Object  attributes  Optional attributes
 * @return HTMLElement
 */
function node(name, attributes) {
    "use strict";
    var _node   = document.createElement(name);
    for (var attrName in attributes) {
        var value = attributes[attrName];
        if (attributes.hasOwnProperty(attrName) && isString(attrName)) {
            _node.setAttribute(attrName, value);
        }
    }
    return _node;
}
exports.node = node;

/**
 * Maps an object to an array made from its values.
 *
 * @param  Object  obj
 * @return Array
 */
function objectValues(obj) {
    "use strict";
    return Object.keys(obj).map(function(arg) {
        return obj[arg];
    });
}
exports.objectValues = objectValues;

/**
 * Prepares a string for xpath expression with the condition [text()=].
 *
 * @param  String  string
 * @return String
 */
function quoteXPathAttributeString(string) {
    "use strict";
    if (/"/g.test(string)) {
        return 'concat("' + string.toString().replace(/"/g, '", \'"\', "') + '")';
    } else {
        return '"' + string + '"';
    }
}
exports.quoteXPathAttributeString = quoteXPathAttributeString;

/**
 * Serializes a value using JSON.
 *
 * @param  Mixed  value
 * @return String
 */
function serialize(value, indent) {
    "use strict";
    if (isArray(value)) {
        value = value.map(function _map(prop) {
            return isFunction(prop) ? prop.toString().replace(/\s{2,}/, '') : prop;
        });
    }
    return JSON.stringify(value, null, indent);
}
exports.serialize = serialize;

/**
 * Returns unique values from an array.
 *
 * Note: ugly code is ugly, but efficient: http://jsperf.com/array-unique2/8
 *
 * @param  Array  array
 * @return Array
 */
function unique(array) {
    "use strict";
    var o = {},
        r = [];
    for (var i = 0, len = array.length; i !== len; i++) {
        var d = array[i];
        if (o[d] !== 1) {
            o[d] = 1;
            r[r.length] = d;
        }
    }
    return r;
}
exports.unique = unique;

/**
 * Compare two version numbers represented as strings.
 *
 * @param  String  a  Version a
 * @param  String  b  Version b
 * @return Number
 */
function cmpVersion(a, b) {
    "use strict";
    var i, cmp, len, re = /(\.0)+[^\.]*$/;
    function versionToString(version) {
        if (isObject(version)) {
            try {
                return [version.major, version.minor, version.patch].join('.');
            } catch (e) {}
        }
        return version;
    }
    a = versionToString(a);
    b = versionToString(b);
    a = (a + '').replace(re, '').split('.');
    b = (b + '').replace(re, '').split('.');
    len = Math.min(a.length, b.length);
    for (i = 0; i < len; i++) {
        cmp = parseInt(a[i], 10) - parseInt(b[i], 10);
        if (cmp !== 0) {
            return cmp;
        }
    }
    return a.length - b.length;
}
exports.cmpVersion = cmpVersion;

/**
 * Checks if a version number string is greater or equals another.
 *
 * @param  String  a  Version a
 * @param  String  b  Version b
 * @return Boolean
 */
function gteVersion(a, b) {
    "use strict";
    return cmpVersion(a, b) >= 0;
}
exports.gteVersion = gteVersion;

/**
 * Checks if a version number string is less than another.
 *
 * @param  String  a  Version a
 * @param  String  b  Version b
 * @return Boolean
 */
function ltVersion(a, b) {
    "use strict";
    return cmpVersion(a, b) < 0;
}
exports.ltVersion = ltVersion;


function debug() {
    var msg = [];
    for ( var i = 0 ; i < arguments.length; i++) {
        if ( isObject(arguments[i]) ) 
            msg.push(JSON.stringify(arguments[i]));
        else if (arguments[i] === undefined )
            msg.push("[undefined]");
        else 
            msg.push(arguments[i]);
    }
    console.log("DEBUG:"+msg.join(""));
}
exports.debug = debug;

function die (err) {
    console.log("ERROR:"+err);
    page.render('phantomjs/images/error1.png');
    phantom.exit();
    return false;
}
exports.die   =  die;

exports.waitFor = function(selector,timeout,cb) {
    if ( timeout < 100) timeout = 100;
    var ic = timeout / 100;
    if (typeof selector === "string" ) {
        selector = function() {
            return $$(selector).exists();
        }
    }
    var itvl = setInterval(function() {
        if (selector()) {
            clearInterval(itvl);
            cb();
        }
        ic--;
        if ( ic <= 0) {
            clearInterval(itvl);
            die("TIMEOUT");
        }
    },100);
}




exports.selectOption = function (selector,value) {
    return page.evaluate(function(selector,value) {
        var idx = null;
        var text2re = null;

        if (selector instanceof Array) {
            if ( selector[1].toFixed ) {
                idx = selector[1];
            } else {
                text2re   = RegExp(selector[1]);
            }
        selector = selector[0];     
      } else {
            text2re   = null;
      }

        var ds = document.querySelectorAll(selector);
        var f = false;
        if ( ds.length == 0) {
            console.log("ERROR:selectOption:selector(" + selector + ") not found");
            return;
        } else if ( ds.length > 1 && idx == null ) {
            console.log("ERROR:selectOption:selector(" + selector + ") contains more than one element");
            return false;
        } else {
            idx = 0;
        }
        ds[idx].value = value;

        return true;
    },selector,value);
}

exports.selectRadio = function(selector,value) {
    return page.evaluate(function(selector,value) {
        var ds = document.querySelectorAll(selector);
        var f = false;
        if ( ds.length == 0) {
            console.log("ERROR:selectRadio:selector(" + selector + ") not found");
            return;
        }       
        for ( var i = 0;i< ds.length;i++) {
            if (ds[i].value == value) {
             ds[i].checked="checked";
             f = true;  
            }
            else ds[i].checked=null;
        }
        if ( ! f ) {
            console.log("ERROR: select radio failed");
            return false;
        }
        return true;
    },selector,value);
}

exports.selectJSSelect = function(value,button,container,element) {
    if ( ! $$(button).click() ) {
        return false;
    }

    var br = page.evaluate(function(selector,selector2) {
        var ds = document.querySelectorAll(selector);
        var text2re = null;
        if ( ds.length == 0) {
            console.log("ERROR:selectJSSelect:selector(" + selector + ") not found");
            return false;
        } else if ( ds.length > 1 ) {
            console.log("ERROR:selectJSSelect:selector(" + selector + ") contains more than one element");
            return false;
        }

    if (selector2 instanceof Array) {
        text2re   = RegExp(selector2[1]);
        selector2 = selector2[0];       
    } else {
            text2re   = null;
    }
    
        var is = ds[0].querySelectorAll(selector2);
        var targetEl = null;
        if (is.length == 0) {
            console.log("ERROR:selectJSSelect:selector2(" + selector2 + ") not found");
            return false;
        }
        if ( text2re == null ) {
            targetEl = is[0];
        } else {
            for ( var i =0 ; i < is.length && ! targetEl ; i++) {
                var t = is[i].innerHTML;
                if (t.match(text2re)) {
                    targetEl = is[i];
                    break;
                }
            }   
        }
            
        if ( targetEl == null ) {
            console.log("ERROR:selectJSSelect:selector2(" + selector2 + ","+text2re+") not found");
            return false;
        }

        var o = targetEl.offsetParent;
        var ob = o.getBoundingClientRect();
        var tb = targetEl.getBoundingClientRect();
        if ( tb.top >= ob.top &&  tb.bottom <= ob.bottom ) {
            return tb;
        } else {
            var sof = (tb.bottom - ob.top - ob.height);
            console.log("DEBUG:set container scrollTop: " + sof);
            o.scrollTop = sof ;
            var tb = targetEl.getBoundingClientRect();
            if ( tb.top >= ob.top &&  tb.bottom <= ob.bottom ) {
                return tb;
            } else {
                console.log("ERROR: Moving Failed");
                return false;
            }
        }

        return false;

    },container,element);
    
    if ( br ) {
        page.sendEvent('click', br.left + br.width / 2, br.top + br.height / 2);        
        return true;
    }
    
    return false;
}



function $$(selector) {
    if ( isObject(selector) && selector._selector ) {
        selector = selector._selector;
    }

    function exists() {
        return page.evaluate(function(selector) {
            if ( ! window._query ) console.log("$$INJECT");
            return _query(selector).length > 0;
        },selector);
    }

    function count() {
        return page.evaluate(function(selector) {
            if ( ! window._query ) console.log("$$INJECT");
            return _query(selector).length;
        },selector);
    }

    function html() {
        return page.evaluate(function(selector) {
            if ( ! window._query ) console.log("$$INJECT");
            var d = _query(selector);
            if ( d == null ) return null;
            else {
                if (d instanceof Array) {
                    var r = "";
                    for ( i = 0 ; i < d.length; i++) {
                        r+=d[0].innerHTML;
                    }   
                    return r;
                } else return d.innerHTML;
            }
        },selector);
    }

    function dump() {
        return page.evaluate(function(selector) {
            if ( ! window._query ) console.log("$$INJECT");
            var d = _query(selector);
            var r = "";
            for ( i = 0 ; i < d.length; i++) {
                r+=d[0].outerHTML + "\n";
            }   
            return r;
        },selector);
    }

    function evaluate(f) {
        return page.evaluate(function(selector,f) {
            if ( ! window._query ) console.log("$$INJECT");
            var d = _query(selector);
            eval("var ex="+f);
            return ex(d);
        },selector,f);
    }

    function each(f) {
        var cnt = count();
        if ( cnt == 1 ) {
            f(this,0);
        } else if ( cnt != 0 ) {
            if (selector instanceof Array) {
                die("NONIMPLEMENTED");
            }

            for ( var i  = 0; i < cnt ; i++) {
                var ns = selector;
                if ( f($$([ns,i]),i) === false ) break;
            }   
        }       
    }

    function eachAsync(f,cb) {
        var cnt = count();

        if ( cnt == 1 ) {
            f(this,0,function(){});
            cb();
        } else if ( cnt != 0 ) {
            if (selector instanceof Array) {
                die("NONIMPLEMENTED");
            }
            var i = 0;
            var ieachCb = function() {
                if ( i >= cnt) cb();
                else {
                    var ns = selector;
                    i++;
                    f($$([ns,i-1]),i-1,ieachCb);
                }
            }
            ieachCb();
        } else {
            cb();
        }
    }

    function attr(attr) {
      return page.evaluate(function(selector,attr) {
        if ( ! window._query ) console.log("$$INJECT");
        var d = _query(selector);
        if ( d.length == 0 ) {
          console.log("ERROR:attr:"+JSON.stringify(selector)+" element not found");
          return null;
        } else if ( d.length > 1 ) {
            console.log("ERROR:attr:"+JSON.stringify(selector)+" many elements");
            return null;
        }
        return d[0].getAttribute(attr);
      },selector,attr);
    }

    function contains(str) {
        return  dump().indexOf(str) != -1;      
    }

    function click() {
        var rect = page.evaluate(function(selector) {
            if ( ! window._query ) console.log("$$INJECT");
            var d = _query(selector);
            if ( d.length == 0 ) {
              console.log("ERROR:click:"+JSON.stringify(selector)+" element not found");
              return null;
            } else if ( d.length > 1 ) {
                console.log("ERROR:click:"+JSON.stringify(selector)+" many elements");
                return null;
            }
            return d[0].getBoundingClientRect();
        },selector);
        if ( rect ) {
            var x = rect.left + rect.width / 2
            var y = rect.top + rect.height / 2;
            debug("click("+x+","+y+")");
            page.sendEvent('click', x, y);
            return true;    
        } else {
            return false;
        }   
    }

    return {
        _selector: selector,
        exists: exists,
        html: html,
        dump: dump,
        each: each,
        attr: attr,
        evaluate: evaluate,
        eachAsync: eachAsync,
        click:click,
        contains: contains
    }
}

exports.$$ = $$;

exports._query = function(selector) {
    var idx = null;
    var text2re = null;
    if (selector instanceof Array) {
        if ( selector[1].toFixed ) {
            idx = selector[1];
        } else {
            text2re   = RegExp(selector[1]);
        }
        selector = selector[0];     
    } else {
        text2re   = null;
  }
  var ds = document.querySelectorAll(selector);
  if ( ds.length == 0) return [];
  if ( idx == null && text2re== null) {
    return ds;
  } else {
    if ( idx != null) {
        if ( idx < ds.length )
            return [ ds[idx] ] ;
        else 
            return [];
    } else 
        return ds;
  }
}
