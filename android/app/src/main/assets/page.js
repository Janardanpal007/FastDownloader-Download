/*
 * FastDownloader page script for the in-app browser. Runs in every page and frame, as early as possible.
 *  1. Element hiding: asks the app which ad selectors apply to this page (site rules, plus generic rules
 *     for the ids and classes actually on the page, as uBlock Origin does) and hides those elements.
 *  2. Video detection: puts a Download button on each video playing on the page. The button lives in a
 *     closed shadow root, so the page can't restyle or script it, and only real taps are acted on.
 *  3. Video playlists the page loads under other names, found by their content, for downloading.
 *  4. Video mode: with the phone turned sideways, the playing video fills the screen.
 *  5. YouTube without Shorts (a setting).
 */
(function () {
  'use strict';
  if (window.__fdPage) return;
  window.__fdPage = true;
  var B = window.__fdBridge;
  if (!B) return;

  // ---------- 0. YouTube without video ads (like uBlock Origin and Brave) ----------
  // Runs before YouTube's own scripts: the ad entries are taken out of the player data, so the player
  // never sees ads. If one still slips through, it's muted and skipped.
  if (/(^|\.)(youtube\.com|youtube-nocookie\.com)$/.test(location.hostname) && B.youtubeAdsOff()) {
    var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams', 'adBreakParams'];
    var removed = 0;
    var strip = function (o, depth) {
      if (!o || typeof o !== 'object' || depth > 4) return o;
      for (var i = 0; i < AD_KEYS.length; i++) if (AD_KEYS[i] in o) { delete o[AD_KEYS[i]]; removed++; }
      if (o.playerResponse) strip(o.playerResponse, depth + 1);
      if (Array.isArray(o)) for (var j = 0; j < o.length; j++) strip(o[j], depth + 1);
      else if (o.response) strip(o.response, depth + 1);
      return o;
    };
    var parse = JSON.parse;
    JSON.parse = function () {
      var r = parse.apply(this, arguments);
      try { strip(r, 0); } catch (e) { /* not ours */ }
      return r;
    };
    var json = Response.prototype.json;
    Response.prototype.json = function () {
      return json.apply(this, arguments).then(function (r) { try { strip(r, 0); } catch (e) { /* not ours */ } return r; });
    };
    ['ytInitialPlayerResponse', 'ytInitialData'].forEach(function (name) {
      var value;
      try {
        Object.defineProperty(window, name, {
          configurable: true,
          get: function () { return value; },
          set: function (v) { value = strip(v, 0); },
        });
      } catch (e) { /* already defined */ }
    });
    setInterval(function () {
      var player = document.querySelector('.ad-showing, .ad-interrupting');
      if (!player) return;
      var v = player.querySelector('video');
      if (v) {
        v.muted = true;
        if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
      }
      var skip = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytm-skip-ad-button, [class*="skip-ad"] button');
      if (skip) skip.click();
      removed++;
    }, 300);
    window.__fdAdsRemoved = function () { return removed; };
  }

  // ---------- YouTube without Shorts ----------
  if (/(^|\.)youtube\.com$/.test(location.hostname) && window.top === window && B.hideShorts()) {
    // A Shorts link opens as a normal video.
    var fromShorts = function () {
      var m = /^\/shorts\/([\w-]{6,})/.exec(location.pathname);
      if (m) location.replace('/watch?v=' + m[1]);
    };
    fromShorts();
    setInterval(fromShorts, 500);
    var shortsCss = [
      // m.youtube.com
      'ytm-reel-shelf-renderer', 'ytm-shorts-lockup-view-model', 'ytm-shorts-lockup-view-model-v2',
      'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2, ytm-reel-shelf-renderer)',
      'grid-shelf-view-model:has(ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2)',
      'ytm-item-section-renderer:has(> * > ytm-reel-shelf-renderer)',
      'ytm-pivot-bar-item-renderer:has(.pivot-shorts)',
      'ytm-video-with-context-renderer:has(a[href^="/shorts/"])', 'ytm-compact-video-renderer:has(a[href^="/shorts/"])',
      'ytm-rich-item-renderer:has(a[href^="/shorts/"])',
      // www.youtube.com
      'ytd-reel-shelf-renderer', 'ytd-rich-shelf-renderer[is-shorts]', 'ytd-rich-section-renderer:has(ytd-rich-shelf-renderer[is-shorts])',
      'ytd-guide-entry-renderer:has(a[title="Shorts"])', 'ytd-mini-guide-entry-renderer[aria-label="Shorts"]',
      'ytd-video-renderer:has(a[href^="/shorts/"])', 'ytd-grid-video-renderer:has(a[href^="/shorts/"])',
      'ytd-rich-item-renderer:has(a[href^="/shorts/"])', 'yt-tab-shape[tab-title="Shorts"]',
      'grid-shelf-view-model:has(a[href^="/shorts/"])',
    ];
    var hideShorts = function () {
      var st = document.createElement('style');
      st.setAttribute('data-fd-shorts', '');
      (document.head || document.documentElement).appendChild(st);
      shortsCss.forEach(function (sel) {
        try { st.sheet.insertRule(sel + '{display:none!important}', st.sheet.cssRules.length); } catch (e) { /* unsupported here */ }
      });
    };
    if (document.documentElement) hideShorts();
    else document.addEventListener('readystatechange', hideShorts, { once: true });
  }

  // ---------- 1. element hiding ----------
  var style = null;
  function sheet() {
    if (!style) {
      style = document.createElement('style');
      style.setAttribute('data-fd', '');
      (document.head || document.documentElement).appendChild(style);
    }
    if (!style.isConnected) (document.head || document.documentElement).appendChild(style);
    return style.sheet;
  }
  function hide(text) {
    if (!text) return;
    var sels = text.split('\n');
    var s = sheet();
    if (!s) return;
    // Groups of 40 per rule; a selector this browser doesn't understand only costs its own group, which is
    // then added one by one.
    for (var i = 0; i < sels.length; i += 40) {
      var group = sels.slice(i, i + 40);
      try {
        s.insertRule(group.join(',\n') + '{display:none!important}', s.cssRules.length);
      } catch (e) {
        group.forEach(function (one) {
          try { s.insertRule(one + '{display:none!important}', s.cssRules.length); } catch (e2) { /* unsupported selector */ }
        });
      }
    }
  }

  var seen = Object.create(null);
  var pending = [];
  var timer = 0;
  function collect(el) {
    if (el.id && !seen['#' + el.id]) { seen['#' + el.id] = 1; pending.push('#' + el.id); }
    var cl = el.classList;
    if (cl) for (var i = 0; i < cl.length; i++) {
      var c = '.' + cl[i];
      if (!seen[c]) { seen[c] = 1; pending.push(c); }
    }
  }
  function scan(root) {
    if (root.nodeType !== 1) return;
    collect(root);
    var all = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < all.length; i++) collect(all[i]);
  }
  function flush() {
    timer = 0;
    if (!pending.length) return;
    var batch = pending.join(' ');
    pending = [];
    try { hide(B.cosmeticFor(location.href, batch)); } catch (e) { /* bridge gone */ }
  }
  function later() { if (!timer) timer = setTimeout(flush, 200); }

  try { hide(B.cosmeticBase(location.href)); } catch (e) { /* bridge gone */ }

  function start() {
    scan(document.documentElement);
    flush();
    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'attributes') collect(m.target);
        else for (var j = 0; j < m.addedNodes.length; j++) scan(m.addedNodes[j]);
      }
      if (pending.length) later();
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['id', 'class'] });
    setInterval(placeButtons, 400);
    addEventListener('scroll', placeButtons, { passive: true, capture: true });
    addEventListener('resize', placeButtons, { passive: true });
  }

  // ---------- 2. Download buttons on videos ----------
  var MIN_W = 200, MIN_H = 110;
  var host = document.createElement('div');
  host.style.cssText = 'all:initial;position:fixed;top:0;left:0;width:0;height:0;z-index:2147483647;';
  var root = host.attachShadow ? host.attachShadow({ mode: 'closed' }) : host;
  var css = document.createElement('style');
  css.textContent =
    // A small round dot with the app's icon, so it hides as little of the video as possible.
    '.b{position:fixed;display:flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:50%;' +
    'background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.45);cursor:pointer;-webkit-tap-highlight-color:transparent;' +
    'user-select:none;opacity:.9}' +
    '.b:active{transform:scale(.9);opacity:1}' +
    '.b svg{width:22px;height:22px}';
  root.appendChild(css);
  var buttons = []; // { video, el }
  var lastCount = -1;
  var lastHref = '';

  function button(video) {
    var el = document.createElement('div');
    el.className = 'b';
    el.setAttribute('role', 'button');
    el.setAttribute('aria-label', 'Download with FastDownloader');
    el.title = 'Download with FastDownloader';
    // Built with DOM calls, not innerHTML: sites like YouTube only allow "Trusted Types" HTML.
    var NS = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    // The app icon: a light blue arrow down and a blue arrow up.
    [['#2f7cf6', '16.5,3 22,9 19,9 19,18 9,18 9,14 14,14 14,9 11,9'],
     ['#3cc6f0', '5,6 15,6 15,10 10,10 10,15 13,15 7.5,21 2,15 5,15']].forEach(function (a) {
      var p = document.createElementNS(NS, 'polygon');
      p.setAttribute('fill', a[0]);
      p.setAttribute('points', a[1]);
      svg.appendChild(p);
    });
    el.appendChild(svg);
    el.addEventListener('click', function (ev) {
      if (!ev.isTrusted) return;
      ev.preventDefault();
      ev.stopPropagation();
      var src = video.currentSrc || video.src || '';
      if (!src) { var s = video.querySelector('source[src]'); if (s) src = s.src; }
      try {
        B.video(JSON.stringify({
          page: location.href,
          top: window.top === window,
          src: src,
          title: document.title,
          width: video.videoWidth || 0,
          height: video.videoHeight || 0,
        }));
      } catch (e) { /* bridge gone */ }
    }, true);
    return el;
  }

  function placeButtons() {
    if (document.fullscreenElement || document.documentElement.hasAttribute('data-fd-video-on')) { host.style.display = 'none'; return; }
    host.style.display = '';
    if (!host.isConnected && document.documentElement) document.documentElement.appendChild(host);
    var vids = document.getElementsByTagName('video');
    var shown = 0;
    // New videos get a button; removed ones lose it.
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (!buttons.some(function (b) { return b.video === v; })) {
        var el = button(v);
        root.appendChild(el);
        buttons.push({ video: v, el: el });
      }
    }
    buttons = buttons.filter(function (b) {
      if (!b.video.isConnected) { b.el.remove(); return false; }
      return true;
    });
    for (var k = 0; k < buttons.length; k++) {
      var b = buttons[k];
      var r = b.video.getBoundingClientRect();
      var visible = r.width >= MIN_W && r.height >= MIN_H && r.bottom > 30 && r.top < innerHeight - 30 &&
        getComputedStyle(b.video).visibility !== 'hidden';
      if (!visible) { b.el.style.display = 'none'; continue; }
      shown++;
      b.el.style.display = 'flex';
      b.el.style.top = Math.max(8, r.top + 8) + 'px';
      // Top left: players keep their own controls (settings, captions) at the top right.
      b.el.style.left = Math.max(8, r.left + 8) + 'px';
      b.el.style.right = 'auto';
    }
    var count = buttons.length;
    if (count !== lastCount || location.href !== lastHref) {
      lastCount = count;
      lastHref = location.href;
      try { B.found(count, location.href, window.top === window); } catch (e) { /* bridge gone */ }
    }
    return shown;
  }

  // ---------- 3. Video playlists hidden behind other names ----------
  // Many sites load their HLS (.m3u8) or DASH (.mpd) playlist from an address that doesn't say so; the
  // content does. Found ones are offered when the video's Download button is tapped.
  (function () {
    var reported = Object.create(null);
    function report(url, kind) {
      if (!url || reported[url]) return;
      reported[url] = 1;
      try { B.media(String(url), kind, location.href); } catch (e) { /* bridge gone */ }
    }
    function sniff(url, text) {
      if (typeof text !== 'string') return;
      var head = text.slice(0, 400).replace(/^\uFEFF/, '').replace(/^\s+/, '');
      if (head.lastIndexOf('#EXTM3U', 0) === 0) report(url, 'm3u8');
      else if (/^(<\?xml[^>]*>\s*)?(<!--[\s\S]*?-->\s*)*<MPD[\s>]/.test(head)) report(url, 'mpd');
    }
    var P = XMLHttpRequest.prototype;
    var open = P.open;
    P.open = function () {
      if (!this.__fdSniff) {
        this.__fdSniff = 1;
        this.addEventListener('load', function () {
          try { if (this.responseType === '' || this.responseType === 'text') sniff(this.responseURL, this.responseText); } catch (e) { /* unreadable */ }
        });
      }
      return open.apply(this, arguments);
    };
    if (!window.fetch) return;
    window.fetch = new Proxy(window.fetch, {
      apply: function (f, self, a) {
        var p = Reflect.apply(f, self, a);
        p.then(function (res) {
          try {
            var ct = (res.headers.get('content-type') || '').toLowerCase();
            var len = +(res.headers.get('content-length') || 0);
            if (/mpegurl/.test(ct)) report(res.url, 'm3u8');
            else if (/dash\+xml/.test(ct)) report(res.url, 'mpd');
            else if ((ct === '' || /^text\/plain|octet-stream/.test(ct)) && len > 0 && len < 524288) {
              res.clone().text().then(function (t) { sniff(res.url, t); }, function () {});
            }
          } catch (e) { /* opaque response */ }
        }, function () {});
        return p;
      },
    });
  })();

  // ---------- 4. Video mode: the phone turned sideways while a video plays ----------
  // The app calls __fdVideoMode(true, [frames with videos]) in the top page. The playing video's player (with
  // its own controls) is pinned over the whole screen; the app hides its own bars. false undoes it.
  if (window.top === window) {
    var vmSheet = null;
    var vmMarked = [];
    var VM_CSS = [
      'html[data-fd-video-on],html[data-fd-video-on] body{overflow:hidden!important}',
      '[data-fd-up]{transform:none!important;filter:none!important;perspective:none!important;contain:none!important;' +
        'will-change:auto!important;z-index:2147483646!important}',
      '[data-fd-video]{position:fixed!important;left:0!important;top:0!important;right:auto!important;bottom:auto!important;' +
        'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;min-width:0!important;' +
        'min-height:0!important;margin:0!important;padding:0!important;border:0!important;border-radius:0!important;' +
        'transform:none!important;aspect-ratio:auto!important;z-index:2147483646!important;background:#000!important}',
      'video[data-fd-video]{object-fit:fill!important}',
      '[data-fd-path]{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;' +
        'transform:none!important;aspect-ratio:auto!important}',
      '[data-fd-vid]{position:absolute!important;left:0!important;top:0!important;width:100%!important;height:100%!important;' +
        'max-width:none!important;max-height:none!important;object-fit:fill!important;transform:none!important}',
    ];
    var vmMark = function (el, attr) { el.setAttribute(attr, ''); vmMarked.push([el, attr]); };
    var vmOff = function () {
      vmMarked.forEach(function (m) { m[0].removeAttribute(m[1]); });
      vmMarked = [];
      if (vmSheet) { vmSheet.remove(); vmSheet = null; }
      document.documentElement.removeAttribute('data-fd-video-on');
    };
    var vmPlaying = function () {
      var vs = document.getElementsByTagName('video'), best = null, area = 0;
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        if (v.paused || v.ended) continue;
        var r = v.getBoundingClientRect();
        if (r.width < 120 || r.height < 68 || r.bottom <= 0 || r.top >= innerHeight) continue;
        if (r.width * r.height > area) { area = r.width * r.height; best = v; }
      }
      return best;
    };
    var vmFrame = function (frames) {
      var list = document.getElementsByTagName('iframe'), best = null, area = 0;
      for (var i = 0; i < list.length; i++) {
        var f = list[i];
        if (frames.indexOf(f.src) < 0) continue;
        var r = f.getBoundingClientRect();
        if (r.width < 120 || r.height < 68) continue;
        if (r.width * r.height > area) { area = r.width * r.height; best = f; }
      }
      return best;
    };
    var vmOn = function (frames) {
      vmOff();
      var video = vmPlaying(), box, kind = 'video';
      if (video) {
        // The player: the largest box around the video that is still about the video's size.
        var vr = video.getBoundingClientRect();
        box = video;
        for (var p = video.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
          var r = p.getBoundingClientRect();
          if (r.width * r.height > vr.width * vr.height * 1.35 + 1) break;
          box = p;
        }
        if (box !== video) {
          for (var e = video.parentElement; e && e !== box; e = e.parentElement) vmMark(e, 'data-fd-path');
          vmMark(video, 'data-fd-vid');
        }
      } else {
        box = vmFrame(frames || []);
        kind = 'frame';
        if (!box) return '';
      }
      vmMark(box, 'data-fd-video');
      for (var up = box.parentElement; up && up !== document.documentElement; up = up.parentElement) vmMark(up, 'data-fd-up');
      document.documentElement.setAttribute('data-fd-video-on', '');
      // Added rule by rule, which pages with a strict Content-Security-Policy still allow.
      vmSheet = document.createElement('style');
      (document.head || document.documentElement).appendChild(vmSheet);
      VM_CSS.forEach(function (rule) { try { vmSheet.sheet.insertRule(rule, vmSheet.sheet.cssRules.length); } catch (e2) { /* skip */ } });
      return kind;
    };
    try {
      Object.defineProperty(window, '__fdVideoMode', {
        value: function (on, frames) {
          try { if (!on) { vmOff(); return ''; } return vmOn(frames); } catch (e) { return ''; }
        },
      });
    } catch (e) { /* already there */ }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
})();
