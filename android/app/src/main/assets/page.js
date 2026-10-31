/*
 * FastDownloader page script for the in-app browser. Runs in every page and frame, as early as possible.
 *  1. Element hiding: asks the app which ad selectors apply to this page (site rules, plus generic rules
 *     for the ids and classes actually on the page, as uBlock Origin does) and hides those elements.
 *  2. Video detection: puts a Download button on each video playing on the page. The button lives in a
 *     closed shadow root, so the page can't restyle or script it, and only real taps are acted on.
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
    if (document.fullscreenElement) { host.style.display = 'none'; return; }
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

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
})();
