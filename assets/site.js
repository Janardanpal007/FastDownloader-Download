// FastDownloader download page.
// - Finds each system's newest file in the GitHub releases (Android-only releases are skipped per file type),
//   and fills in links, sizes, version and SHA-256 digests. Without JS or the API, the links in the HTML
//   already point at the v1.5.0 files.
// - Points the main button at the visitor's system, drives the 3D laptop tour, the picker and install tabs.
(function () {
  'use strict';

  var REPO = 'Janardanpal007/FastDownloader-Download';
  var PAGE = 'https://janardanpal007.github.io/FastDownloader-Download/';
  var MATCH = {
    windows: /setup.*\.exe$/i,
    'mac-arm': /arm64\.dmg$/i,
    'mac-intel': /x64\.dmg$/i,
    appimage: /\.appimage$/i,
    deb: /\.deb$/i,
    'ext-chrome': /extension-chrome.*\.zip$/i,
    'ext-firefox': /extension-firefox.*\.(zip|xpi)$/i
  };
  var OS_NAMES = { windows: 'Windows', mac: 'macOS', linux: 'Linux' };
  var DEFAULT_PICK = { windows: 'windows', mac: 'mac-arm', linux: 'appimage' };
  var DEFAULT_ANGLE = '-14';
  var $ = function (s, root) { return (root || document).querySelector(s); };
  var $$ = function (s, root) { return Array.prototype.slice.call((root || document).querySelectorAll(s)); };

  function detectOs() {
    var ua = navigator.userAgent || '';
    if (/android|iphone|ipad/i.test(ua)) return null;
    var p = ((navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || ua).toLowerCase();
    if (p.indexOf('win') >= 0) return 'windows';
    if (p.indexOf('mac') >= 0) return 'mac';
    if (p.indexOf('linux') >= 0) return 'linux';
    return null;
  }

  function mb(bytes) { return (bytes / 1048576).toFixed(1) + ' MB'; }

  var os = detectOs();
  var files = {};

  /* ---------- Download picker ---------- */
  function currentKey() {
    var checked = $('input[name="pkg"]:checked');
    return checked ? checked.value : 'windows';
  }

  function updatePicker() {
    var key = currentKey();
    var opt = $('input[name="pkg"][value="' + key + '"]');
    var go = $('#pick-download');
    var file = files[key];
    if (file) go.href = file.browser_download_url;
    else go.href = opt.getAttribute('data-href');
    $('#pick-label').textContent = 'Download ' + opt.getAttribute('data-label');
    var digest = file && file.digest ? file.digest.replace(/^sha256:/, '') : '';
    $('#digest').textContent = digest || 'See SHA256SUMS.txt in the release.';
    $('#copy-digest').hidden = !digest;
    $('#copy-status').textContent = '';
  }

  function setupPicker() {
    $$('input[name="pkg"]').forEach(function (r) { r.addEventListener('change', updatePicker); });
    var pick = DEFAULT_PICK[os];
    if (pick) {
      $('input[name="pkg"][value="' + pick + '"]').checked = true;
      $$('[data-for="' + os + '"]').forEach(function (b) { b.hidden = false; });
    }
    $('#copy-digest').addEventListener('click', function () {
      var status = $('#copy-status');
      if (!navigator.clipboard) { status.textContent = 'Select the digest above and copy it.'; return; }
      navigator.clipboard.writeText($('#digest').textContent)
        .then(function () { status.textContent = 'SHA-256 copied.'; })
        .catch(function () { status.textContent = 'Select the digest above and copy it.'; });
    });
    updatePicker();
  }

  /* ---------- Hero button ---------- */
  function updateHero(version) {
    var btn = $('#hero-download');
    var label = $('#hero-label');
    var meta = $('#hero-meta');
    if (!os) { label.textContent = 'Choose your download'; return; }
    if (os === 'mac') {
      label.textContent = 'Choose your Mac download';
      btn.href = '#download';
      meta.textContent = 'For macOS · Apple Silicon or Intel' + (version ? ' · v' + version : '');
      return;
    }
    label.textContent = 'Download for ' + OS_NAMES[os];
    var key = DEFAULT_PICK[os];
    var file = files[key];
    btn.href = file ? file.browser_download_url : $('input[name="pkg"][value="' + key + '"]').getAttribute('data-href');
    if (file) meta.textContent = 'For ' + OS_NAMES[os] + ' · ' + mb(file.size) + (version ? ' · v' + version : '');
  }

  /* ---------- Releases ---------- */
  function applyReleases(releases) {
    var version = '';
    var trusted = 'https://github.com/' + REPO + '/releases/download/';
    releases
      .filter(function (r) { return !r.draft && !r.prerelease; })
      .sort(function (a, b) { return String(b.published_at).localeCompare(String(a.published_at)); })
      .forEach(function (release) {
      (release.assets || []).forEach(function (a) {
        if (typeof a.browser_download_url !== 'string' || a.browser_download_url.indexOf(trusted) !== 0) return;
        Object.keys(MATCH).forEach(function (key) {
          if (!files[key] && MATCH[key].test(a.name)) {
            files[key] = a;
            if (key === 'windows') version = (release.tag_name || '').replace(/^v/, '');
          }
        });
      });
    });
    Object.keys(files).forEach(function (key) {
      $$('[data-asset="' + key + '"]').forEach(function (a) { a.href = files[key].browser_download_url; });
      var size = $('[data-size="' + key + '"]');
      if (size) size.textContent = mb(files[key].size);
    });
    if (version) $$('.version').forEach(function (v) { v.textContent = 'v' + version; });
    updatePicker();
    updateHero(version);
  }

  /* ---------- 3D laptop tour ---------- */
  var tour = {
    downloads: { caption: 'See active downloads, connection details and progress together in the main window.', image: 'images/main-window.png', title: 'Main window' },
    video: { caption: 'Choose your video format and quality before adding it to your downloads.', image: 'images/video-dialog.png', title: 'Add a video download' },
    privacy: { caption: 'Explore settings for proxies, Tor, tracking protection and how you keep history.', image: 'images/privacy.png', title: 'Privacy settings' }
  };
  var view = 'downloads';
  var drag = null;
  var dragMoved = false;

  function showView(key, focus) {
    if (!tour[key]) return;
    view = key;
    $('#screen').setAttribute('data-view', key);
    $('#tour-caption').textContent = tour[key].caption;
    $('#tour-panel').setAttribute('aria-labelledby', 'tab-' + key);
    $$('.tour-tab').forEach(function (t) {
      var on = t.getAttribute('data-view') === key;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
      if (on && focus) t.focus();
    });
  }

  function setupTour() {
    var tabs = $$('.tour-tab');
    tabs.forEach(function (t, i) {
      t.addEventListener('click', function () { showView(t.getAttribute('data-view')); });
      t.addEventListener('keydown', function (e) {
        var next = { ArrowRight: (i + 1) % tabs.length, ArrowLeft: (i + tabs.length - 1) % tabs.length, Home: 0, End: tabs.length - 1 }[e.key];
        if (next === undefined) return;
        e.preventDefault();
        showView(tabs[next].getAttribute('data-view'), true);
      });
    });

    var laptop = $('#laptop');
    var angle = $('#angle');
    function setAngle() { laptop.style.setProperty('--angle', angle.value + 'deg'); }
    angle.addEventListener('input', setAngle);
    $('#reset-angle').addEventListener('click', function () { angle.value = DEFAULT_ANGLE; setAngle(); });
    laptop.addEventListener('pointerdown', function (e) {
      if (e.button !== 0) return;
      e.preventDefault();
      drag = { x: e.clientX, y: e.clientY, start: Number(angle.value) };
      dragMoved = false;
    });
    window.addEventListener('pointermove', function (e) {
      if (!drag) return;
      var dx = e.clientX - drag.x, dy = e.clientY - drag.y;
      if (!dragMoved && Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 10) { drag = null; return; }
      if (Math.abs(dx) > 6) dragMoved = true;
      if (dragMoved) { angle.value = String(Math.max(-30, Math.min(30, drag.start + dx / 3))); setAngle(); }
    }, { passive: true });
    // Clear the drag flag after any click it should suppress, so a drag released elsewhere
    // doesn't swallow the next keyboard open.
    window.addEventListener('pointerup', function () { drag = null; setTimeout(function () { dragMoved = false; }, 0); });
    window.addEventListener('pointercancel', function () { drag = null; });
    setAngle();

    var dialog = $('#shot-dialog');
    $('#screen').addEventListener('click', function (e) {
      if (dragMoved) { e.preventDefault(); dragMoved = false; return; }
      var t = tour[view];
      $('#shot-title').textContent = t.title;
      $('#shot-img').src = t.image;
      $('#shot-img').alt = 'FastDownloader: ' + t.title.toLowerCase();
      dialog.showModal();
    });
    $('#shot-close').addEventListener('click', function () { dialog.close(); });
    dialog.addEventListener('click', function (e) { if (e.target === dialog) dialog.close(); });
  }

  /* ---------- Motion ---------- */
  function setupMotion() {
    var btn = $('#motion');
    var reduce = window.matchMedia('(prefers-reduced-motion: reduce)');
    function apply(paused) {
      document.body.classList.toggle('motion-paused', paused);
      btn.textContent = paused ? 'Play motion' : 'Pause motion';
      btn.disabled = reduce.matches;
      if (reduce.matches) btn.textContent = 'Motion off';
    }
    btn.addEventListener('click', function () { apply(!document.body.classList.contains('motion-paused')); });
    reduce.addEventListener('change', function () { apply(reduce.matches); });
    apply(reduce.matches);
    $$('.bars i').forEach(function (b, i) { b.style.setProperty('--i', i); });
  }

  /* ---------- Install tabs ---------- */
  function showInstall(key) {
    $$('.os-tab').forEach(function (t) {
      var on = t.getAttribute('data-os') === key;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
    });
    $$('.os-panel').forEach(function (p) { p.hidden = p.id !== 'install-' + key; });
  }

  function setupInstall() {
    var tabs = $$('.os-tab');
    tabs.forEach(function (t, i) {
      t.addEventListener('click', function () { showInstall(t.getAttribute('data-os')); });
      t.addEventListener('keydown', function (e) {
        var next = { ArrowRight: (i + 1) % tabs.length, ArrowLeft: (i + tabs.length - 1) % tabs.length, Home: 0, End: tabs.length - 1 }[e.key];
        if (next === undefined) return;
        e.preventDefault();
        showInstall(tabs[next].getAttribute('data-os'));
        tabs[next].focus();
      });
    });
    showInstall(os || 'windows');
  }

  /* ---------- Menu and share ---------- */
  function setupMenu() {
    var toggle = $('#menu-toggle');
    var nav = $('#mobile-nav');
    function set(open) {
      toggle.setAttribute('aria-expanded', String(open));
      nav.hidden = !open;
    }
    toggle.addEventListener('click', function () { set(toggle.getAttribute('aria-expanded') !== 'true'); });
    $$('a', nav).forEach(function (a) { a.addEventListener('click', function () { set(false); }); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !nav.hidden) { set(false); toggle.focus(); } });
    window.matchMedia('(min-width: 821px)').addEventListener('change', function (m) { if (m.matches) set(false); });
  }

  function setupShare() {
    var text = 'FastDownloader: a free, fast download manager for Windows, macOS and Linux';
    var share = {
      x: 'https://x.com/intent/post?text=' + encodeURIComponent(text) + '&url=' + encodeURIComponent(PAGE),
      whatsapp: 'https://wa.me/?text=' + encodeURIComponent(text + ' ' + PAGE),
      reddit: 'https://www.reddit.com/submit?url=' + encodeURIComponent(PAGE) + '&title=' + encodeURIComponent(text),
      linkedin: 'https://www.linkedin.com/sharing/share-offsite/?url=' + encodeURIComponent(PAGE)
    };
    $$('[data-share]').forEach(function (a) { a.href = share[a.getAttribute('data-share')]; });
    var copy = $('#copy-link');
    copy.addEventListener('click', function () {
      var done = function (msg) { copy.textContent = msg; setTimeout(function () { copy.textContent = 'Copy link'; }, 2000); };
      if (!navigator.clipboard) { done(PAGE); return; }
      navigator.clipboard.writeText(PAGE).then(function () { done('Copied'); }).catch(function () { done(PAGE); });
    });
  }

  setupPicker();
  updateHero('');
  setupTour();
  setupMotion();
  setupInstall();
  setupMenu();
  setupShare();

  fetch('https://api.github.com/repos/' + REPO + '/releases?per_page=30', { headers: { Accept: 'application/vnd.github+json' } })
    .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('GitHub API ' + r.status)); })
    .then(applyReleases)
    .catch(function () { /* Offline or rate-limited: the v1.5.0 links already in the HTML still work. */ });
})();
