var unsafeWindow = window;
var GM_info = { script: { name: 'userscript', version: '1.0' }, scriptHandler: 'WebApk' };
var GM_log = function () { console.log.apply(console, arguments); };
var GM_addStyle = function (css) {
  var s = document.createElement('style');
  s.textContent = css;
  (document.head || document.documentElement).appendChild(s);
  return s;
};
var GM_setValue = function (k, v) { try { localStorage.setItem('__gm_' + k, JSON.stringify(v)); } catch (e) {} };
var GM_getValue = function (k, d) {
  try { var v = localStorage.getItem('__gm_' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; }
};
var GM_deleteValue = function (k) { try { localStorage.removeItem('__gm_' + k); } catch (e) {} };
var GM_openInTab = function (u) { window.open(u, '_blank'); };
var GM_setClipboard = function (t) { try { navigator.clipboard.writeText(t); } catch (e) {} };
var GM_xmlhttpRequest = function (d) {
  var o = { method: d.method || 'GET', headers: d.headers || {}, body: d.data, credentials: 'include' };
  fetch(d.url, o).then(function (r) {
    return r.text().then(function (t) {
      var res = { status: r.status, statusText: r.statusText, responseText: t, response: t, finalUrl: r.url, responseHeaders: '' };
      if (d.onload) d.onload(res);
    });
  }).catch(function (e) { if (d.onerror) d.onerror(e); });
};
var GM = {
  info: GM_info,
  addStyle: function (c) { return Promise.resolve(GM_addStyle(c)); },
  setValue: function (k, v) { return Promise.resolve(GM_setValue(k, v)); },
  getValue: function (k, d) { return Promise.resolve(GM_getValue(k, d)); },
  deleteValue: function (k) { return Promise.resolve(GM_deleteValue(k)); },
  openInTab: GM_openInTab,
  setClipboard: GM_setClipboard,
  xmlHttpRequest: GM_xmlhttpRequest
};
