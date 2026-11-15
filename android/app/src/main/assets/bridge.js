/* MTS Staff — Android bridge.
   Runs inside the app's WebView before the web app's own code. It gives the web app the phone features
   that a plain WebView lacks (notifications, saving and sharing files, printing, copy, voice, opening WhatsApp),
   by passing them to the native Android code through window.MTSNative. The web app itself is unchanged. */
(function () {
  'use strict';
  if (window.__mtsBridge) return;
  var N = window.MTSNative;
  if (!N) return;
  window.__mtsBridge = true;

  var SCOPE = N.scope();
  var pending = {}, seq = 0;
  function call(fnName, args) {
    return new Promise(function (resolve) {
      var id = 'c' + (++seq);
      pending[id] = resolve;
      N[fnName].apply(N, [id].concat(args || []));
    });
  }
  // native code answers here
  window.__mtsResolve = function (id, value) {
    var fn = pending[id]; delete pending[id];
    if (fn) fn(value);
  };

  function absUrl(u) { try { return new URL(u, location.href).href; } catch (e) { return String(u); } }
  function inScope(u) { return absUrl(u).indexOf(SCOPE) === 0; }

  function blobToBase64(blob) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () { var s = String(r.result); resolve(s.slice(s.indexOf(',') + 1)); };
      r.onerror = function () { reject(r.error); };
      r.readAsDataURL(blob);
    });
  }

  /* ---------- the app's files come from the phone itself, so the website's service worker isn't needed ---------- */
  if (window.ServiceWorkerContainer && navigator.serviceWorker) {
    try {
      var SWC = ServiceWorkerContainer.prototype, getRegs = SWC.getRegistrations;
      if (getRegs) getRegs.call(navigator.serviceWorker).then(function (rs) { rs.forEach(function (r) { r.unregister(); }); }).catch(function () { });
      SWC.register = function () { return Promise.reject(new Error('Not used in the Android app')); };
      SWC.getRegistration = function () { return Promise.resolve(undefined); };
      SWC.getRegistrations = function () { return Promise.resolve([]); };
    } catch (e) { }
  }

  /* ---------- notifications ---------- */
  function notify(title, opts) {
    opts = opts || {};
    N.showNotification(String(title || ''), String(opts.body || ''), String(opts.tag || ''));
  }
  function MTSNotification(title, opts) {
    if (!(this instanceof MTSNotification)) throw new TypeError("Failed to construct 'Notification'");
    this.title = title; this.body = (opts && opts.body) || ''; this.tag = (opts && opts.tag) || '';
    this.onclick = null; this.onclose = null; this.onshow = null; this.onerror = null;
    if (N.notifPermission() === 'granted') notify(title, opts);
  }
  MTSNotification.prototype.close = function () { if (this.tag) N.cancelNotify(String(this.tag)); };
  MTSNotification.prototype.addEventListener = function () { };
  MTSNotification.prototype.removeEventListener = function () { };
  Object.defineProperty(MTSNotification, 'permission', { get: function () { return N.notifPermission(); } });
  MTSNotification.requestPermission = function (cb) {
    return call('requestNotifPermission').then(function (p) { if (typeof cb === 'function') cb(p); return p; });
  };
  MTSNotification.maxActions = 0;
  try { Object.defineProperty(window, 'Notification', { value: MTSNotification, writable: true, configurable: true }); }
  catch (e) { window.Notification = MTSNotification; }
  if (window.ServiceWorkerRegistration) {
    ServiceWorkerRegistration.prototype.showNotification = function (title, opts) {
      if (N.notifPermission() === 'granted') notify(title, opts);
      return Promise.resolve();
    };
    ServiceWorkerRegistration.prototype.getNotifications = function () { return Promise.resolve([]); };
  }

  /* ---------- saving files (reports, Excel sheets) ---------- */
  function saveBlob(blob, name, alsoShare) {
    return blobToBase64(blob).then(function (b64) {
      return call('saveFile', [String(name || 'file'), String(blob.type || 'application/octet-stream'), b64, !!alsoShare]);
    });
  }
  // the web app downloads through <a download href="blob:..."> — catch that and save natively
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a') : null;
    if (!a || !a.href) return;
    var href = a.href;
    if (a.hasAttribute('download') && /^(blob:|data:)/.test(href)) {
      ev.preventDefault(); ev.stopPropagation();
      var name = a.getAttribute('download') || 'file';
      fetch(href).then(function (r) { return r.blob(); }).then(function (b) { return saveBlob(b, name, false); })
        .catch(function () { N.toast('save_failed'); });
      return;
    }
    if (!inScope(href) && /^(https?:|tel:|mailto:|sms:|whatsapp:|intent:|geo:|upi:)/i.test(href)) {
      ev.preventDefault(); ev.stopPropagation();
      N.openExternal(absUrl(href));
    }
  }, true);

  /* ---------- share (Web Share API) ---------- */
  navigator.canShare = function (data) {
    if (!data) return false;
    return !!(data.files && data.files.length) || !!data.text || !!data.url || !!data.title;
  };
  navigator.share = function (data) {
    data = data || {};
    if (data.files && data.files.length) {
      return Promise.all(Array.prototype.map.call(data.files, function (f) {
        return blobToBase64(f).then(function (b64) { return { name: f.name || 'file', type: f.type || 'application/octet-stream', data: b64 }; });
      })).then(function (files) {
        return call('shareFiles', [JSON.stringify(files), String(data.title || ''), String(data.text || '')]);
      }).then(function () { });
    }
    var text = [data.text, data.url].filter(Boolean).join('\n');
    N.shareText(String(data.title || ''), text);
    return Promise.resolve();
  };

  /* ---------- copy ---------- */
  var clip = navigator.clipboard;
  var writeText = function (text) { N.copy(String(text)); return Promise.resolve(); };
  if (clip) { try { clip.writeText = writeText; } catch (e) { } }
  else { try { Object.defineProperty(navigator, 'clipboard', { value: { writeText: writeText, readText: function () { return Promise.resolve(''); } }, configurable: true }); } catch (e) { } }

  /* ---------- print ---------- */
  window.print = function () { N.print(); };

  /* ---------- links that open other apps (WhatsApp etc.) ---------- */
  var origOpen = window.open;
  window.open = function (url, target, features) {
    if (url && !inScope(url)) { N.openExternal(absUrl(url)); return null; }
    if (url) { location.href = absUrl(url); return window; }
    return origOpen ? origOpen.call(window, url, target, features) : null;
  };

  /* ---------- voice: read answers aloud ---------- */
  function Utterance(text) {
    this.text = text || ''; this.lang = ''; this.rate = 1; this.pitch = 1; this.volume = 1; this.voice = null;
    this.onend = null; this.onstart = null; this.onerror = null;
  }
  Utterance.prototype.addEventListener = function () { };
  var synth = {
    speaking: false, pending: false, paused: false, onvoiceschanged: null,
    speak: function (u) { if (u) N.ttsSpeak(String(u.text || ''), String(u.lang || ''), Number(u.rate) || 1); },
    cancel: function () { N.ttsStop(); },
    pause: function () { }, resume: function () { },
    getVoices: function () { return []; },
    addEventListener: function () { }, removeEventListener: function () { }
  };
  try {
    Object.defineProperty(window, 'SpeechSynthesisUtterance', { value: Utterance, writable: true, configurable: true });
    Object.defineProperty(window, 'speechSynthesis', { value: synth, writable: true, configurable: true });
  } catch (e) { }

  /* ---------- voice: ask by speaking ---------- */
  if (N.sttAvailable()) {
    var recs = {};
    function Rec() {
      this.lang = ''; this.interimResults = false; this.maxAlternatives = 1; this.continuous = false;
      this.onresult = null; this.onend = null; this.onerror = null; this.onstart = null;
      this._id = 'r' + (++seq); recs[this._id] = this;
    }
    Rec.prototype.start = function () { N.sttStart(this._id, String(this.lang || ''), !!this.interimResults); };
    Rec.prototype.stop = function () { N.sttStop(); };
    Rec.prototype.abort = function () { N.sttStop(); };
    Rec.prototype.addEventListener = function () { };
    window.__mtsStt = function (id, type, text, isFinal, error) {
      var r = recs[id]; if (!r) return;
      if (type === 'start') { if (r.onstart) r.onstart({}); return; }
      if (type === 'result') {
        var res = [{ transcript: text, confidence: 0.9 }]; res.isFinal = !!isFinal;
        if (r.onresult) r.onresult({ results: [res], resultIndex: 0 });
        return;
      }
      if (type === 'error') { if (r.onerror) r.onerror({ error: error }); return; }
      if (type === 'end') { delete recs[id]; if (r.onend) r.onend({}); }
    };
    try {
      Object.defineProperty(window, 'SpeechRecognition', { value: Rec, writable: true, configurable: true });
      Object.defineProperty(window, 'webkitSpeechRecognition', { value: Rec, writable: true, configurable: true });
    } catch (e) { }
  }

  /* ---------- status bar colour follows the app's light / dark theme ---------- */
  function syncThemeColor() {
    var m = document.querySelector('meta[name="theme-color"]');
    if (m && m.content) N.setThemeColor(m.content);
  }
  function watchTheme() {
    syncThemeColor();
    var m = document.querySelector('meta[name="theme-color"]');
    if (m && window.MutationObserver) new MutationObserver(syncThemeColor).observe(m, { attributes: true, attributeFilter: ['content'] });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watchTheme); else watchTheme();

  /* ---------- Android back button ---------- */
  window.__mtsBack = function () {
    try {
      var modal = document.getElementById('modal-root');
      if (modal && modal.children.length) { if (typeof closeModal === 'function') closeModal(); else modal.innerHTML = ''; return 'modal'; }
      if (typeof UI !== 'undefined' && UI) {
        if (UI.menuOpen) { UI.menuOpen = false; if (typeof render === 'function') render(); return 'menu'; }
        if (typeof me === 'function' && typeof navItems === 'function' && typeof go === 'function') {
          var u = me();
          if (u) { var items = navItems(u); if (items && items.length && UI.view && UI.view !== items[0][0]) { go(items[0][0]); return 'home'; } }
        }
      }
    } catch (e) { }
    return '';
  };
})();
