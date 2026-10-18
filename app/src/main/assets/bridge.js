(function () {
  if (window.__webApkBridge || !window.AndroidBridge) return;
  window.__webApkBridge = true;
  var B = window.AndroidBridge;

  function N(title, opts) {
    opts = opts || {};
    this.title = title;
    this.body = opts.body || '';
    this.tag = opts.tag;
    this.data = opts.data;
    this.icon = opts.icon;
    this.onclick = null; this.onclose = null; this.onerror = null; this.onshow = null;
    try { B.showNotification(String(title), String(this.body)); } catch (e) {}
    var self = this;
    setTimeout(function () { if (typeof self.onshow === 'function') self.onshow(); }, 0);
  }
  N.prototype.close = function () {};
  N.prototype.addEventListener = function () {};
  N.prototype.removeEventListener = function () {};
  N.maxActions = 2;
  Object.defineProperty(N, 'permission', {
    get: function () { return B.hasNotifPermission() ? 'granted' : 'default'; }
  });
  N.requestPermission = function (cb) {
    return new Promise(function (resolve) {
      if (B.hasNotifPermission()) { if (cb) cb('granted'); return resolve('granted'); }
      B.requestNotifPermission();
      var t = 0;
      var iv = setInterval(function () {
        t += 300;
        var g = B.hasNotifPermission();
        if (g || t > 30000) {
          clearInterval(iv);
          var r = g ? 'granted' : 'denied';
          if (cb) cb(r);
          resolve(r);
        }
      }, 300);
    });
  };

  try { Object.defineProperty(window, 'Notification', { value: N, writable: true, configurable: true }); }
  catch (e) { window.Notification = N; }

  try {
    if (window.ServiceWorkerRegistration) {
      ServiceWorkerRegistration.prototype.showNotification = function (title, opts) {
        new N(title, opts);
        return Promise.resolve();
      };
    }
  } catch (e) {}

  try {
    var q = navigator.permissions && navigator.permissions.query;
    if (q) {
      navigator.permissions.query = function (d) {
        if (d && d.name === 'notifications') {
          return Promise.resolve({ state: B.hasNotifPermission() ? 'granted' : 'prompt', onchange: null });
        }
        return q.call(navigator.permissions, d);
      };
    }
  } catch (e) {}
})();
