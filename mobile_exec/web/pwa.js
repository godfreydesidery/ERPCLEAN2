/*
 * Orbix App - the page side of the PWA.
 *
 *  - registers the service worker (sw.js) and watches for a new version;
 *  - captures the browser's install prompt (Chrome/Edge/Samsung Internet) and
 *    knows when to explain the manual path instead (iPhone/iPad Safari);
 *  - tracks online/offline;
 *  - exposes all of that to the Flutter app as window.orbixPwa
 *    (lib/pwa/pwa_bridge_web.dart reads it).
 *
 * Updates are NEVER applied behind the user's back: a new version waits until
 * the user taps "Update now", so a half-filled form is never wiped by a reload.
 *
 * Plain ES2017, no dependencies, no inline script (the CSP forbids it).
 */
(function () {
  'use strict';

  // printing (PDF preview) loads pdf.js from this site, not from unpkg.com.
  window.dartPdfJsBaseUrl = 'pdfjs/';

  var HINT_KEY = 'orbix.pwa.installHintSnoozedUntil';
  var HINT_SNOOZE_DAYS = 30;
  var UPDATE_CHECK_MS = 30 * 60 * 1000;
  var LOAD_STALL_MS = 25000;

  var listeners = [];
  var deferredPrompt = null;
  var registration = null;
  var reloading = false;

  var ua = navigator.userAgent || '';
  // iPadOS 13+ reports itself as a Mac; touch points give it away.
  var isIos = /iPhone|iPad|iPod/.test(ua) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

  function isStandalone() {
    return (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches) ||
      navigator.standalone === true;
  }

  function readSnooze() {
    try {
      return Number(window.localStorage.getItem(HINT_KEY)) || 0;
    } catch (e) {
      return 0;
    }
  }

  var state = {
    online: navigator.onLine !== false,
    standalone: isStandalone(),
    canPrompt: false,
    ios: isIos,
    installed: false,
    swSupported: 'serviceWorker' in navigator && window.isSecureContext === true,
    swActive: false,
    updateReady: false,
    hintSnoozed: readSnooze() > Date.now(),
    version: '',
  };

  function emit() {
    var json = JSON.stringify(state);
    for (var i = 0; i < listeners.length; i++) {
      try {
        listeners[i](json);
      } catch (e) {
        /* a broken listener must not stop the others */
      }
    }
  }

  function set(patch) {
    var changed = false;
    for (var k in patch) {
      if (Object.prototype.hasOwnProperty.call(patch, k) && state[k] !== patch[k]) {
        state[k] = patch[k];
        changed = true;
      }
    }
    if (changed) emit();
  }

  // ------------------------------------------------------------ connectivity
  window.addEventListener('online', function () {
    set({ online: true });
    if (registration) registration.update().catch(function () {});
  });
  window.addEventListener('offline', function () { set({ online: false }); });

  // ------------------------------------------------------------ install
  window.addEventListener('beforeinstallprompt', function (e) {
    // Keep the browser's mini-infobar quiet; the app shows its own, snoozable hint.
    e.preventDefault();
    deferredPrompt = e;
    set({ canPrompt: true });
  });
  window.addEventListener('appinstalled', function () {
    deferredPrompt = null;
    set({ canPrompt: false, installed: true });
  });
  if (window.matchMedia) {
    var mq = window.matchMedia('(display-mode: standalone)');
    var onMode = function () { set({ standalone: isStandalone() }); };
    if (mq.addEventListener) mq.addEventListener('change', onMode);
  }

  // ------------------------------------------------------------ service worker
  function watchInstalling(worker) {
    if (!worker) return;
    worker.addEventListener('statechange', function () {
      // "installed" with a controller already in place = an UPDATE is waiting.
      // Without a controller it is the very first install: nothing to announce.
      if (worker.state === 'installed' && navigator.serviceWorker.controller) {
        set({ updateReady: true });
      }
    });
  }

  function askVersion(worker) {
    if (!worker) return;
    var channel = new MessageChannel();
    channel.port1.onmessage = function (ev) {
      if (ev.data && ev.data.version) set({ version: String(ev.data.version) });
    };
    worker.postMessage({ type: 'GET_VERSION' }, [channel.port2]);
  }

  // Resources fetched before the worker took control (first visit) are not in
  // its cache yet - most importantly the CanvasKit engine, which is chosen per
  // browser and so is not precached. Hand their URLs over so the next launch
  // works offline.
  function warmCache() {
    if (!navigator.serviceWorker.controller || !window.performance) return;
    var urls = [];
    var entries = performance.getEntriesByType('resource');
    for (var i = 0; i < entries.length; i++) {
      var u;
      try {
        u = new URL(entries[i].name);
      } catch (e) {
        continue;
      }
      if (u.origin === location.origin && u.pathname.indexOf('/api/') !== 0) {
        urls.push(u.pathname);
      }
    }
    if (urls.length) {
      navigator.serviceWorker.controller.postMessage({ type: 'CACHE_URLS', urls: urls });
    }
  }

  function registerWorker() {
    if (!state.swSupported) return;
    navigator.serviceWorker
      .register('sw.js', { scope: './', updateViaCache: 'none' })
      .then(function (reg) {
        registration = reg;
        if (reg.waiting && navigator.serviceWorker.controller) set({ updateReady: true });
        watchInstalling(reg.installing);
        reg.addEventListener('updatefound', function () { watchInstalling(reg.installing); });

        setInterval(function () {
          if (navigator.onLine !== false) reg.update().catch(function () {});
        }, UPDATE_CHECK_MS);
        document.addEventListener('visibilitychange', function () {
          if (document.visibilityState === 'visible' && navigator.onLine !== false) {
            reg.update().catch(function () {});
          }
        });
        return navigator.serviceWorker.ready;
      })
      .then(function (reg) {
        if (!reg) return;
        set({ swActive: true });
        askVersion(reg.active);
        if (navigator.serviceWorker.controller) {
          warmCache();
        } else {
          // First visit: wait until the new worker claims this page.
          navigator.serviceWorker.addEventListener('controllerchange', function once() {
            navigator.serviceWorker.removeEventListener('controllerchange', once);
            warmCache();
          });
        }
      })
      .catch(function (err) {
        // The app still works online without a worker - just no offline/install.
        if (window.console) console.warn('Orbix: service worker registration failed', err);
      });

    navigator.serviceWorker.addEventListener('controllerchange', function () {
      // Only reload when the USER asked for the update (applyUpdate). The very
      // first claim on a fresh install also fires this event, and must not.
      if (reloading) window.location.reload();
    });
  }

  // ------------------------------------------------------------ loading screen
  var started = false;
  function splash(id) { return document.getElementById(id); }

  setTimeout(function () {
    if (started) return;
    var msg = splash('orbix-splash-msg');
    var retry = splash('orbix-splash-retry');
    if (!msg || !retry) return;
    msg.textContent = navigator.onLine === false
      ? 'You are offline and Orbix App has not been saved on this device yet. Connect to the internet and try again.'
      : 'Orbix App is taking longer than usual to load. Check your connection and try again.';
    retry.hidden = false;
    retry.onclick = function () { window.location.reload(); };
  }, LOAD_STALL_MS);

  // ------------------------------------------------------------ API for Flutter
  window.orbixPwa = {
    /** Current state as a JSON string (see lib/pwa/pwa_state.dart). */
    stateJson: function () { return JSON.stringify(state); },

    /** Calls cb(jsonString) on every change. */
    subscribe: function (cb) {
      if (typeof cb === 'function') listeners.push(cb);
    },

    /**
     * Shows the browser's own install dialog. Resolves to "accepted",
     * "dismissed" or "unavailable" (no prompt captured - e.g. iOS).
     */
    promptInstall: function () {
      if (!deferredPrompt) return Promise.resolve('unavailable');
      var p = deferredPrompt;
      deferredPrompt = null; // a prompt can be shown once only
      set({ canPrompt: false });
      return p.prompt().then(function () { return p.userChoice; }).then(function (choice) {
        if (choice && choice.outcome === 'accepted') set({ installed: true });
        return (choice && choice.outcome) || 'dismissed';
      }, function () { return 'dismissed'; });
    },

    /** "Not now" on the install hint: stay quiet for 30 days. */
    snoozeInstallHint: function () {
      try {
        window.localStorage.setItem(HINT_KEY, String(Date.now() + HINT_SNOOZE_DAYS * 864e5));
      } catch (e) {
        /* storage blocked (private mode): the snooze lasts this visit only */
      }
      set({ hintSnoozed: true });
    },

    /** Activates the waiting version and reloads - only ever on user request. */
    applyUpdate: function () {
      if (!registration || !registration.waiting) {
        window.location.reload();
        return;
      }
      reloading = true;
      registration.waiting.postMessage({ type: 'SKIP_WAITING' });
    },

    /** Called by flutter_bootstrap.js once the app has drawn. */
    appStarted: function () {
      started = true;
      var s = splash('orbix-splash');
      if (s && s.parentNode) s.parentNode.removeChild(s);
    },
  };

  registerWorker();
})();
