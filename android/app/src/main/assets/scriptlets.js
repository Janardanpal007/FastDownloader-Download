/*
 * FastDownloader scriptlets for the in-app browser: the small page scripts uBlock Origin's filter lists
 * switch on for particular sites ("example.com##+js(name, args…)"), to take out ads, pop-ups and
 * "turn off your ad blocker" walls that network blocking alone can't. Written for this app; same names
 * and arguments as uBlock Origin's, so the lists work as written.
 *
 * Runs in every page and frame before the page's own scripts. The app sends only the scriptlets for
 * this site (nothing when ad blocking is off for it).
 */
(function () {
  'use strict';
  if (window.__fdScriptlets) return;
  try { Object.defineProperty(window, '__fdScriptlets', { value: true }); } catch (e) { return; }
  var B = window.__fdBridge;
  if (!B || !B.scriptlets) return;
  var list;
  try { list = JSON.parse(B.scriptlets(location.href, window.top === window)); } catch (e) { return; }
  if (!list || !list.length) return;

  // Errors thrown on purpose to stop an ad script carry this word and are kept out of the console.
  var magic = 'fd' + Math.random().toString(36).slice(2);
  window.addEventListener('error', function (ev) {
    if (String(ev.message || '').indexOf(magic) >= 0) { ev.preventDefault(); ev.stopImmediatePropagation(); }
  }, true);

  var parseJSON = JSON.parse;
  var stringify = JSON.stringify;
  var BAD = {};

  // "text", "/regex/flags", "!negated" → { test(string) }. Empty matches everything.
  function pat(s, allowNot) {
    s = s == null ? '' : String(s);
    var not = false;
    if (allowNot !== false && s.charAt(0) === '!') { not = true; s = s.slice(1); }
    var re = toRegex(s, '');
    return { empty: re === null, test: function (t) { var r = re === null || re.test(String(t)); return not ? !r : r; } };
  }
  function toRegex(s, extra) {
    if (s === '') return null;
    var m = /^\/(.+)\/([dgimsuy]*)$/.exec(s);
    try {
      if (m) return new RegExp(m[1], m[2].replace('g', '') + extra);
      return new RegExp(s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), extra);
    } catch (e) {
      return /(?!)/;
    }
  }
  function whenDom(fn) {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn, { once: true });
    else fn();
  }
  function isObj(v) { return v !== null && (typeof v === 'object' || typeof v === 'function'); }

  // ----- properties: set-constant, abort-on-property-read/write, abort-current-script, abort-on-stack-trace -----

  // Calls leaf(owner, name) for the last part of "a.b.c", now or as soon as a and a.b exist.
  function trapChain(chain, leaf, owner) {
    owner = owner || window;
    var dot = chain.indexOf('.');
    if (dot < 0) { leaf(owner, chain); return; }
    var prop = chain.slice(0, dot), rest = chain.slice(dot + 1);
    var v;
    try { v = owner[prop]; } catch (e) { return; }
    if (isObj(v)) { trapChain(rest, leaf, v); return; }
    var d = Object.getOwnPropertyDescriptor(owner, prop);
    if (d && d.configurable === false) return;
    var cur = v;
    try {
      Object.defineProperty(owner, prop, {
        configurable: true, enumerable: true,
        get: function () { return cur; },
        set: function (a) { cur = a; if (isObj(a)) trapChain(rest, leaf, a); },
      });
    } catch (e) { /* not ours to change */ }
  }

  var noop = function () {};
  function constant(v) {
    switch (v) {
      case 'undefined': return undefined;
      case 'false': return false;
      case 'true': return true;
      case 'null': return null;
      case '': case "''": case 'emptyStr': return '';
      case '[]': case 'emptyArr': return [];
      case '{}': case 'emptyObj': return {};
      case 'noopFunc': return function () {};
      case 'trueFunc': return function () { return true; };
      case 'falseFunc': return function () { return false; };
      case 'throwFunc': return function () { throw new Error(magic); };
      case 'noopCallbackFunc': return function () { return noop; };
      case 'yes': case 'no': case 'on': case 'off': return v;
      case 'NaN': return NaN;
      case 'Infinity': return Infinity;
    }
    if (/^-?\d+$/.test(v)) { var n = parseInt(v, 10); if (Math.abs(n) <= 0x7FFF) return n; }
    return BAD;
  }

  function setConstant(chain, raw) {
    var value = constant(raw == null ? 'undefined' : raw);
    if (value === BAD || !chain) return;
    trapChain(chain, function (o, p) {
      var d = Object.getOwnPropertyDescriptor(o, p);
      if (d && d.configurable === false) { try { o[p] = value; } catch (e) { /* read-only */ } return; }
      try {
        Object.defineProperty(o, p, { configurable: true, enumerable: true, get: function () { return value; }, set: noop });
      } catch (e) { /* not ours to change */ }
    });
  }

  function abortOnRead(chain) {
    if (!chain) return;
    trapChain(chain, function (o, p) {
      try {
        Object.defineProperty(o, p, { configurable: true, get: function () { throw new ReferenceError(magic); }, set: noop });
      } catch (e) { /* not ours to change */ }
    });
  }

  function abortOnWrite(chain) {
    if (!chain) return;
    trapChain(chain, function (o, p) {
      try {
        Object.defineProperty(o, p, { configurable: true, set: function () { throw new ReferenceError(magic); } });
      } catch (e) { /* not ours to change */ }
    });
  }

  // Wraps a property so that check() runs on every read and write; check throws to stop the script.
  function guard(chain, check) {
    trapChain(chain, function (o, p) {
      var d = Object.getOwnPropertyDescriptor(o, p) || {};
      var val = d.get ? undefined : o[p];
      var g = d.get, s = d.set;
      try {
        Object.defineProperty(o, p, {
          configurable: true,
          get: function () { check(); return g ? g.call(this) : val; },
          set: function (a) { check(); if (s) s.call(this, a); else val = a; },
        });
      } catch (e) { /* not ours to change */ }
    });
  }

  function abortCurrentScript(chain, needle, context) {
    if (!chain) return;
    var re = pat(needle), ctx = pat(context);
    var me = document.currentScript;
    guard(chain, function () {
      var e = document.currentScript;
      if (!(e instanceof HTMLScriptElement) || e === me) return;
      if (context && !ctx.test(e.src)) return;
      if (!re.test(e.src ? '' : e.textContent)) return;
      throw new ReferenceError(magic);
    });
  }

  function abortOnStackTrace(chain, needle) {
    if (!chain) return;
    var re = pat(needle);
    if (re.empty) return;
    guard(chain, function () {
      var stack = '';
      try { stack = String(new Error().stack || ''); } catch (e) { /* none */ }
      if (re.test(stack)) throw new ReferenceError(magic);
    });
  }

  // ----- timers, events, eval -----

  function preventTimer(name, needle, delay) {
    var re = pat(needle);
    var d = delay == null ? '' : String(delay);
    var notDelay = d.charAt(0) === '!';
    if (notDelay) d = d.slice(1);
    var n = d === '' ? null : parseInt(d, 10);
    window[name] = new Proxy(window[name], {
      apply: function (f, self, a) {
        var hit = re.test(String(a[0])) && (n === null || ((a[1] === n) !== notDelay));
        if (hit) a[0] = noop;
        return Reflect.apply(f, self, a);
      },
    });
  }

  function boostTimer(name, needle, delay, factor) {
    var re = pat(needle);
    var d = delay === '*' ? null : parseInt(delay || '1000', 10);
    var b = parseFloat(factor || '0.05');
    if (!(b > 0)) b = 0.05;
    b = Math.min(50, Math.max(0.001, b));
    window[name] = new Proxy(window[name], {
      apply: function (f, self, a) {
        if ((d === null || a[1] === d) && re.test(String(a[0]))) a[1] = (a[1] || 0) * b;
        return Reflect.apply(f, self, a);
      },
    });
  }

  function preventListener(type, needle) {
    var t = pat(type), re = pat(needle);
    var P = EventTarget.prototype;
    P.addEventListener = new Proxy(P.addEventListener, {
      apply: function (f, self, a) {
        var h = a[1], text = '';
        try { text = typeof h === 'function' ? String(h) : h && typeof h.handleEvent === 'function' ? String(h.handleEvent) : String(h); } catch (e) { /* unreadable */ }
        if (t.test(a[0]) && re.test(text)) return undefined;
        return Reflect.apply(f, self, a);
      },
    });
  }

  function noEval(needle) {
    var re = pat(needle);
    window.eval = new Proxy(window.eval, {
      apply: function (f, self, a) { if (re.test(String(a[0]))) return undefined; return Reflect.apply(f, self, a); },
    });
  }

  // ----- pop-ups -----

  function fakeWindow() {
    return new Proxy({}, {
      get: function (t, k) {
        if (k === 'closed') return false;
        if (k === 'window' || k === 'self' || k === 'opener') return null;
        if (k === 'document') return document.implementation.createHTMLDocument('');
        return noop;
      },
      set: function () { return true; },
    });
  }

  function noWindowOpen(needle) {
    needle = needle == null ? '' : String(needle);
    var not = needle.charAt(0) === '!';
    var re = pat(not ? needle.slice(1) : needle, false);
    window.open = new Proxy(window.open, {
      apply: function (f, self, a) {
        var url = a.length ? String(a[0]) : '';
        if (re.test(url) === not) return Reflect.apply(f, self, a);
        try { B.popupBlocked(url); } catch (e) { /* bridge gone */ }
        return fakeWindow();
      },
    });
  }

  // ----- JSON, fetch and XMLHttpRequest -----

  function paths(s) { return String(s || '').split(/ +/).filter(Boolean); }

  // Every [owner, key] the path "a.[].b.*" points at in obj.
  function find(o, parts, i, out) {
    if (!isObj(o)) return;
    var k = parts[i], last = i === parts.length - 1;
    if (k === '[-]' && Array.isArray(o)) {
      // Array items that contain the rest of the path are removed as a whole.
      for (var j = 0; j < o.length; j++) {
        var found = [];
        if (last) found.push(1); else find(o[j], parts, i + 1, found);
        if (found.length) out.push([o, j, true]);
      }
      return;
    }
    if (k === '[]' || k === '*') {
      var keys = Array.isArray(o) ? o.map(function (_, x) { return x; }) : Object.keys(o);
      for (var m = 0; m < keys.length; m++) {
        if (last) out.push([o, keys[m]]);
        else find(o[keys[m]], parts, i + 1, out);
      }
      return;
    }
    if (!(k in o)) return;
    if (last) out.push([o, k]);
    else find(o[k], parts, i + 1, out);
  }
  function locate(obj, path) { var out = []; find(obj, path.split('.'), 0, out); return out; }

  function prune(obj, prunePaths, needPaths) {
    if (!isObj(obj)) return false;
    for (var i = 0; i < needPaths.length; i++) if (!locate(obj, needPaths[i]).length) return false;
    var changed = false;
    prunePaths.forEach(function (p) {
      var hits = locate(obj, p);
      // Remove array items from the end, so earlier indexes stay right.
      for (var j = hits.length - 1; j >= 0; j--) {
        var h = hits[j];
        if (h[2] || Array.isArray(h[0])) h[0].splice(h[1], 1); else delete h[0][h[1]];
        changed = true;
      }
    });
    return changed;
  }

  var jsonRules = [];
  var jsonHooked = false;
  function jsonPrune(rawPrune, rawNeed) {
    var p = paths(rawPrune);
    if (!p.length) return;
    jsonRules.push([p, paths(rawNeed)]);
    if (jsonHooked) return;
    jsonHooked = true;
    JSON.parse = new Proxy(JSON.parse, {
      apply: function (f, self, a) {
        var r = Reflect.apply(f, self, a);
        jsonRules.forEach(function (x) { prune(r, x[0], x[1]); });
        return r;
      },
    });
    var json = Response.prototype.json;
    Response.prototype.json = new Proxy(json, {
      apply: function (f, self, a) {
        return Reflect.apply(f, self, a).then(function (r) {
          jsonRules.forEach(function (x) { prune(r, x[0], x[1]); });
          return r;
        });
      },
    });
  }

  // "url:/x/ method:POST" or a plain URL pattern → test(url, method). Empty matches nothing.
  function requestMatcher(raw) {
    raw = raw == null ? '' : String(raw).trim();
    if (raw === '') return null;
    var conds = [];
    raw.split(/\s+/).forEach(function (tok) {
      var m = /^(\w+):(.*)$/.exec(tok);
      if (m && m[1] !== 'http' && m[1] !== 'https' && m[1] !== 'data') conds.push([m[1], pat(m[2])]);
      else conds.push(['url', pat(tok)]);
    });
    return function (url, method) {
      return conds.every(function (c) {
        if (c[0] === 'url') return c[1].test(url);
        if (c[0] === 'method') return c[1].test(method);
        return true;
      });
    };
  }
  // The URL condition of the response scriptlets: "propsToMatch, x" or a bare pattern.
  function urlCondition(args, from) {
    for (var i = from; i < args.length; i += 2) if (args[i] === 'propsToMatch') return requestMatcher(args[i + 1]);
    return args[from] ? requestMatcher(args[from]) : function () { return true; };
  }

  var fetchEdits = [], fetchBlocks = [], xhrEdits = [], xhrBlocks = [];
  var fetchHooked = false, xhrHooked = false;

  function hookFetch() {
    if (fetchHooked || !window.fetch) return;
    fetchHooked = true;
    window.fetch = new Proxy(window.fetch, {
      apply: function (f, self, a) {
        var url = '', method = 'GET';
        try {
          if (a[0] instanceof Request) { url = a[0].url; method = a[0].method; } else url = String(a[0]);
          if (a[1] && a[1].method) method = String(a[1].method).toUpperCase();
        } catch (e) { /* odd arguments */ }
        if (fetchBlocks.some(function (m) { return m(url, method); })) {
          return Promise.resolve(new Response('', { status: 200, statusText: 'OK' }));
        }
        var edits = fetchEdits.filter(function (e) { return e.match(url, method); });
        var p = Reflect.apply(f, self, a);
        if (!edits.length) return p;
        return p.then(function (res) {
          return res.clone().text().then(function (text) {
            var t = text;
            edits.forEach(function (e) { t = e.edit(t); });
            if (t === text) return res;
            var out = new Response(t, { status: res.status, statusText: res.statusText, headers: res.headers });
            try {
              Object.defineProperties(out, { url: { value: res.url }, type: { value: res.type }, redirected: { value: res.redirected } });
            } catch (e) { /* fine without */ }
            return out;
          }, function () { return res; });
        });
      },
    });
  }

  function hookXhr() {
    if (xhrHooked || !window.XMLHttpRequest) return;
    xhrHooked = true;
    var P = XMLHttpRequest.prototype;
    var open = P.open, send = P.send;
    P.open = function (m, u) {
      this.__fdReq = { method: String(m || 'GET').toUpperCase(), url: String(u) };
      return open.apply(this, arguments);
    };
    P.send = function () {
      var req = this.__fdReq;
      if (req && xhrBlocks.some(function (m) { return m(req.url, req.method); })) {
        var xhr = this;
        try {
          Object.defineProperties(xhr, {
            readyState: { value: 4, configurable: true }, status: { value: 200, configurable: true },
            statusText: { value: 'OK', configurable: true }, responseText: { value: '', configurable: true },
            response: { value: '', configurable: true },
          });
        } catch (e) { /* fine */ }
        setTimeout(function () {
          ['readystatechange', 'load', 'loadend'].forEach(function (t) { try { xhr.dispatchEvent(new Event(t)); } catch (e) { /* gone */ } });
        }, 1);
        return undefined;
      }
      return send.apply(this, arguments);
    };
    var dText = Object.getOwnPropertyDescriptor(P, 'responseText');
    var dResp = Object.getOwnPropertyDescriptor(P, 'response');
    function edited(xhr, raw) {
      var req = xhr.__fdReq;
      if (!req || xhr.readyState !== 4 || typeof raw !== 'string') return raw;
      if (req.src === raw) return req.out;
      var t = raw;
      xhrEdits.forEach(function (e) { if (e.match(req.url, req.method)) t = e.edit(t); });
      req.src = raw;
      req.out = t;
      return t;
    }
    if (dText && dText.get) {
      Object.defineProperty(P, 'responseText', { configurable: true, enumerable: true, get: function () { return edited(this, dText.get.call(this)); } });
    }
    if (dResp && dResp.get) {
      Object.defineProperty(P, 'response', {
        configurable: true, enumerable: true,
        get: function () {
          var raw = dResp.get.call(this);
          var req = this.__fdReq;
          if (req && this.readyState === 4 && isObj(raw) && this.responseType === 'json') {
            xhrEdits.forEach(function (e) { if (e.match(req.url, req.method) && e.obj) e.obj(raw); });
            return raw;
          }
          return edited(this, raw);
        },
      });
    }
  }

  function jsonEdit(prunePaths, needPaths) {
    return function (text) {
      var o;
      try { o = parseJSON(text); } catch (e) { return text; }
      return prune(o, prunePaths, needPaths) ? stringify(o) : text;
    };
  }

  function jsonPruneResponse(kind, args) {
    var p = paths(args[0]);
    if (!p.length) return;
    var need = paths(args[1]);
    var edit = { match: urlCondition(args, 2), edit: jsonEdit(p, need), obj: function (o) { prune(o, p, need); } };
    if (kind === 'fetch') { fetchEdits.push(edit); hookFetch(); } else { xhrEdits.push(edit); hookXhr(); }
  }

  function replaceResponse(kind, args) {
    if (!args[0]) return;
    var re = toRegex(args[0], 'g');
    var to = args[1] == null ? '' : args[1];
    var edit = {
      match: args[2] ? requestMatcher(args[2]) : function () { return true; },
      edit: function (text) { return text.replace(re, to); },
    };
    if (kind === 'fetch') { fetchEdits.push(edit); hookFetch(); } else { xhrEdits.push(edit); hookXhr(); }
  }

  function noRequest(kind, raw) {
    var m = requestMatcher(raw);
    if (!m) return;
    if (kind === 'fetch') { fetchBlocks.push(m); hookFetch(); } else { xhrBlocks.push(m); hookXhr(); }
  }

  // ----- page content: remove-attr, remove-class, remove-node-text, replace-node-text -----

  function removeFromElements(kind, tokens, selector, behavior) {
    var names = String(tokens || '').split(/\s*\|\s*/).filter(Boolean);
    if (!names.length) return;
    var sel = selector || names.map(function (n) { return kind === 'attr' ? '[' + n + ']' : '.' + CSS.escape(n); }).join(',');
    function run() {
      var els;
      try { els = document.querySelectorAll(sel); } catch (e) { return; }
      for (var i = 0; i < els.length; i++) {
        for (var j = 0; j < names.length; j++) {
          if (kind === 'attr') els[i].removeAttribute(names[j]); else els[i].classList.remove(names[j]);
        }
      }
    }
    var timer = 0;
    whenDom(function () {
      run();
      addEventListener('load', run, { once: true });
      if (/stay/.test(behavior || '')) {
        new MutationObserver(function () { if (!timer) timer = setTimeout(function () { timer = 0; run(); }, 50); })
          .observe(document.documentElement, { childList: true, subtree: true, attributes: true });
      }
    });
  }

  var textRules = [];
  function nodeText(nodeName, pattern, replacement) {
    if (!nodeName || !pattern) return;
    var name = String(nodeName).toLowerCase();
    var re = toRegex(pattern, replacement == null ? '' : 'g');
    if (!re) return;
    textRules.push({ name: name, re: re, to: replacement });
    if (textRules.length > 1) return;
    var handle = function (n) {
      var nn = n.nodeName.toLowerCase();
      for (var i = 0; i < textRules.length; i++) {
        var r = textRules[i];
        if (r.name !== nn) continue;
        var t = n.textContent;
        r.re.lastIndex = 0;
        if (!r.re.test(t)) continue;
        if (r.to == null) { n.textContent = ''; if (n.parentNode) n.parentNode.removeChild(n); return; }
        r.re.lastIndex = 0;
        n.textContent = t.replace(r.re, r.to);
      }
    };
    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) handle(added[j]);
      }
    }).observe(document, { childList: true, subtree: true });
  }

  // ----- storage and cookies -----

  var STORE_VALUES = /^(undefined|false|true|null|''|-?\d+|emptyArr|emptyObj|yes|no|on|off|ok|accept|accepted|reject|rejected|allow|deny)$/i;
  function setStorageItem(store, key, value) {
    if (!key) return;
    try {
      var s = window[store];
      if (value === '$remove$') { s.removeItem(key); return; }
      if (!STORE_VALUES.test(value || '')) return;
      var map = { "''": '', emptyArr: '[]', emptyObj: '{}' };
      s.setItem(key, value in map ? map[value] : value);
    } catch (e) { /* storage blocked */ }
  }

  function removeCookie(needle) {
    var re = pat(needle);
    function run() {
      document.cookie.split(';').forEach(function (c) {
        var name = c.split('=')[0].trim();
        if (!name || !re.test(name)) return;
        var gone = '=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
        var host = location.hostname;
        document.cookie = name + gone;
        document.cookie = name + gone + '; path=/';
        document.cookie = name + gone + '; path=/; domain=' + host;
        document.cookie = name + gone + '; path=/; domain=.' + host.split('.').slice(-2).join('.');
      });
    }
    run();
    whenDom(run);
    addEventListener('beforeunload', run);
  }

  var COOKIE_VALUES = /^(true|false|yes|no|ok|on|off|accept|accepted|reject|rejected|allow|deny|-?\d+|''|)$/i;
  function setCookie(name, value, path) {
    if (!name || !COOKIE_VALUES.test(value || '')) return;
    var v = value === "''" ? '' : value;
    try { document.cookie = encodeURIComponent(name) + '=' + v + '; path=' + (path || '/') + '; max-age=31536000'; } catch (e) { /* blocked */ }
  }

  // ----- the scriptlets by name (the app turns uBlock's short names into these) -----

  var RUN = {
    'set-constant': function (a) { setConstant(a[0], a[1]); },
    'abort-on-property-read': function (a) { abortOnRead(a[0]); },
    'abort-on-property-write': function (a) { abortOnWrite(a[0]); },
    'abort-current-script': function (a) { abortCurrentScript(a[0], a[1], a[2]); },
    'abort-on-stack-trace': function (a) { abortOnStackTrace(a[0], a[1]); },
    'prevent-setTimeout': function (a) { preventTimer('setTimeout', a[0], a[1]); },
    'prevent-setInterval': function (a) { preventTimer('setInterval', a[0], a[1]); },
    'nano-setTimeout-booster': function (a) { boostTimer('setTimeout', a[0], a[1], a[2]); },
    'nano-setInterval-booster': function (a) { boostTimer('setInterval', a[0], a[1], a[2]); },
    'prevent-addEventListener': function (a) { preventListener(a[0], a[1]); },
    'noeval': function () { noEval(''); },
    'noeval-if': function (a) { noEval(a[0]); },
    'no-window-open-if': function (a) { noWindowOpen(a[0]); },
    'json-prune': function (a) { jsonPrune(a[0], a[1]); },
    'json-prune-fetch-response': function (a) { jsonPruneResponse('fetch', a); },
    'json-prune-xhr-response': function (a) { jsonPruneResponse('xhr', a); },
    'trusted-replace-fetch-response': function (a) { replaceResponse('fetch', a); },
    'trusted-replace-xhr-response': function (a) { replaceResponse('xhr', a); },
    'no-fetch-if': function (a) { noRequest('fetch', a[0]); },
    'no-xhr-if': function (a) { noRequest('xhr', a[0]); },
    'nowebrtc': function () {
      ['RTCPeerConnection', 'webkitRTCPeerConnection'].forEach(function (n) {
        if (!window[n]) return;
        window[n] = function () {
          return {
            close: noop, addEventListener: noop, removeEventListener: noop, createDataChannel: function () { return {}; },
            createOffer: function () { return Promise.reject(new Error(magic)); },
            setLocalDescription: function () { return Promise.reject(new Error(magic)); },
          };
        };
      });
    },
    'remove-attr': function (a) { removeFromElements('attr', a[0], a[1], a[2]); },
    'remove-class': function (a) { removeFromElements('class', a[0], a[1], a[2]); },
    'remove-node-text': function (a) { nodeText(a[0], a[1], null); },
    'trusted-replace-node-text': function (a) { nodeText(a[0], a[1], a[2] == null ? '' : a[2]); },
    'set-local-storage-item': function (a) { setStorageItem('localStorage', a[0], a[1]); },
    'set-session-storage-item': function (a) { setStorageItem('sessionStorage', a[0], a[1]); },
    'remove-cookie': function (a) { removeCookie(a[0]); },
    'set-cookie': function (a) { setCookie(a[0], a[1], a[2]); },
    'window.name-defuser': function () { if (window === window.top) window.name = ''; },
    'alert-buster': function () { window.alert = new Proxy(window.alert, { apply: noop }); },
  };

  for (var i = 0; i < list.length; i++) {
    var run = RUN[list[i][0]];
    if (run) {
      try { run(list[i].slice(1)); } catch (e) { /* one broken scriptlet doesn't stop the others */ }
    }
  }
})();
