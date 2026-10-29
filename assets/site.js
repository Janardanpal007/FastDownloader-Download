// FastDownloader download page: fills in the newest release's files and sizes,
// points the main button at the visitor's system, and wires the share buttons.
(function () {
  'use strict';

  var REPO = 'Janardanpal007/FastDownloader-Download';
  var LATEST = 'https://github.com/' + REPO + '/releases/latest';
  var PAGE = 'https://janardanpal007.github.io/FastDownloader-Download/';

  // Which release file belongs to which button.
  var MATCH = {
    windows: /setup.*\.exe$/i,
    'mac-arm': /arm64\.dmg$/i,
    'mac-intel': /x64\.dmg$/i,
    appimage: /\.appimage$/i,
    deb: /\.deb$/i,
    'ext-chrome': /extension-chrome.*\.zip$/i,
    'ext-firefox': /extension-firefox.*\.(zip|xpi)$/i,
    'android-arm64': /android-arm64\.apk$/i,
    'android-universal': /android-universal\.apk$/i,
  };

  function detectOs() {
    if (/android/i.test(navigator.userAgent)) return 'android';
    var p = ((navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || navigator.userAgent || '').toLowerCase();
    if (p.indexOf('win') >= 0) return 'windows';
    if (p.indexOf('mac') >= 0) return 'mac';
    if (p.indexOf('linux') >= 0 && navigator.userAgent.indexOf('Android') < 0) return 'linux';
    return null;
  }

  function mb(bytes) {
    return Math.round(bytes / 1048576) + ' MB';
  }

  var os = detectOs();
  var heroBtn = document.getElementById('hero-download');
  var heroLabel = document.getElementById('hero-label');
  var heroMeta = document.getElementById('hero-meta');
  var names = { windows: 'Windows', mac: 'macOS', linux: 'Linux', android: 'Android' };
  if (os) {
    heroLabel.textContent = 'Download for ' + names[os];
    var cards = document.querySelectorAll('.platform[data-os="' + os + '"]');
    for (var i = 0; i < cards.length; i++) cards[i].classList.add('mine');
  }

  function setAssets(release) {
    var version = (release.tag_name || '').replace(/^v/, '');
    var files = {};
    (release.assets || []).forEach(function (a) {
      Object.keys(MATCH).forEach(function (key) {
        if (MATCH[key].test(a.name)) files[key] = a;
      });
    });
    Object.keys(files).forEach(function (key) {
      var links = document.querySelectorAll('[data-asset="' + key + '"]');
      for (var i = 0; i < links.length; i++) links[i].href = files[key].browser_download_url;
      var size = document.querySelector('[data-size="' + key + '"]');
      if (size) size.textContent = files[key].name.replace(/^FastDownloader-/, '') + ' · ' + mb(files[key].size);
    });
    if (version) {
      var tags = document.querySelectorAll('.version');
      for (var j = 0; j < tags.length; j++) tags[j].textContent = 'v' + version;
    }
    // Windows, Linux and Android get a direct download from the main button; Macs pick their chip below.
    var direct = { windows: files.windows, linux: files.appimage, android: files['android-arm64'] }[os];
    var kind = { windows: 'For Windows · Installer (64-bit) · ', linux: 'For Linux · AppImage · ', android: 'For Android 8+ · APK · ' }[os];
    if (direct) {
      heroBtn.href = direct.browser_download_url;
      heroMeta.innerHTML = '';
      heroMeta.textContent = kind + mb(direct.size) + ' · v' + version;
    } else if (os === 'mac') {
      heroMeta.textContent = 'For macOS · Apple Silicon or Intel · v' + version;
    }
  }

  fetch('https://api.github.com/repos/' + REPO + '/releases/latest', { headers: { Accept: 'application/vnd.github+json' } })
    .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
    .then(setAssets)
    .catch(function () {
      // Offline or rate-limited: every button still leads to the releases page.
      var links = document.querySelectorAll('[data-asset]');
      for (var i = 0; i < links.length; i++) links[i].href = LATEST;
    });

  // Share buttons
  var text = 'FastDownloader: a fast, private download manager for Windows, macOS, Linux and Android';
  var share = {
    x: 'https://x.com/intent/post?text=' + encodeURIComponent(text) + '&url=' + encodeURIComponent(PAGE),
    whatsapp: 'https://wa.me/?text=' + encodeURIComponent(text + ' ' + PAGE),
    reddit: 'https://www.reddit.com/submit?url=' + encodeURIComponent(PAGE) + '&title=' + encodeURIComponent(text),
    linkedin: 'https://www.linkedin.com/sharing/share-offsite/?url=' + encodeURIComponent(PAGE),
  };
  var shareLinks = document.querySelectorAll('[data-share]');
  for (var k = 0; k < shareLinks.length; k++) shareLinks[k].href = share[shareLinks[k].getAttribute('data-share')];

  // Floating Download card appears once the hero has scrolled away.
  var floatCta = document.querySelector('.float-cta');
  function toggleFloat() { floatCta.classList.toggle('show', window.scrollY > window.innerHeight * 0.7); }
  window.addEventListener('scroll', toggleFloat, { passive: true });
  toggleFloat();

  var copy = document.getElementById('copy-link');
  copy.addEventListener('click', function () {
    var label = copy.querySelector('span');
    (navigator.clipboard ? navigator.clipboard.writeText(PAGE) : Promise.reject())
      .then(function () { label.textContent = 'Copied'; })
      .catch(function () { label.textContent = PAGE; })
      .then(function () { setTimeout(function () { label.textContent = 'Copy link'; }, 2000); });
  });
})();
