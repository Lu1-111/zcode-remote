package com.lululu.zcoderemote.save

/**
 * 「保存副本」注入页面的文档起始脚本（方案第 16 条）。
 *
 * 脚本只做四件事，多一件都不做：
 * 1. 包装 `URL.createObjectURL` / `revokeObjectURL`，把这次注入之后创建的 Blob 记在小表里（限量 4 条）；
 * 2. 在捕获阶段拦截带 `download` 且 `href` 是记过的 blob 地址的 `<a>` 点击——页面的保存是程序触发的
 *    `a.click()`，事件照样冒泡到 document，所以拦得到；不是记过的地址一律不拦，走页面默认行为；
 * 3. 拦到之后把文件名、大小发给原生，等原生选好保存位置再按 [SaveProtocol.CHUNK_BYTES] 一块一块
 *    发 ArrayBuffer，每块等一次 ack；
 * 4. 任何一步出问题都就地收拾（停发、忘掉那个 Blob），绝不抛错打扰页面。
 *
 * 不改页面 DOM，不碰 localStorage，不引别的全局名字（只有 `__zcodeSaveInstalled` 一个哨兵，防重复注入）。
 * 脚本和原生之间的消息只有两种：JSON 字符串（控制消息）和裸 ArrayBuffer（数据块）。
 */
object SaveCopyScript {

    /** 注入的 JS 对象名：页面通过 `window.webkit.messageHandlers.zcodeSave.postMessage(...)` 发消息。 */
    const val JS_OBJECT_NAME: String = "zcodeSave"

    /** 允许注入的来源，也是方案第 16 条定的唯一一条：只对官方手机页生效。 */
    const val ALLOWED_ORIGIN: String = "https://zcode.z.ai"

    /** 记住的 Blob 最多几个：页面的习惯是建一个、点完就撤销，4 个已经宽裕，内存上有底。 */
    const val MAX_REMEMBERED_BLOBS: Int = 4

    /**
     * 生成文档起始脚本。[chunkBytes] 必须就是 [SaveProtocol.CHUNK_BYTES]（桥里是这么传的），
     * 由 SaveCopyScriptTest 钉住两边一致。
     *
     * [arrayBufferSupported] 为 false 时（WebView 收不了 ArrayBuffer 消息）：脚本照常注入、
     * 照常拦到点击，但只向原生报一声 `unsupported` 就撒手——原生据此提示「暂不支持保存」
     * （方案第 16 条「任何一步不支持就退化为提示」在能感知到点击时的兑现）。
     */
    fun build(
        chunkBytes: Int = SaveProtocol.CHUNK_BYTES,
        arrayBufferSupported: Boolean = true,
    ): String = """
(function () {
  'use strict';
  try {
    if (window.__zcodeSaveInstalled) return;
    var handler = window.webkit && window.webkit.messageHandlers &&
      window.webkit.messageHandlers.$JS_OBJECT_NAME;
    if (!handler || typeof handler.postMessage !== 'function') return;
    window.__zcodeSaveInstalled = true;

    var MAX = ${SaveProtocol.MAX_FILE_BYTES};
    var CHUNK = $chunkBytes;
    var CAP = $MAX_REMEMBERED_BLOBS;
    var CAN_CHUNK = $arrayBufferSupported;
    var remembered = new Map();
    var order = [];

    function forget(url) {
      remembered.delete(url);
      var i = order.indexOf(url);
      if (i >= 0) order.splice(i, 1);
    }
    function remember(url, blob) {
      if (remembered.has(url)) return;
      remembered.set(url, blob);
      order.push(url);
      while (order.length > CAP) forget(order[0]);
    }

    var origCreate = URL.createObjectURL.bind(URL);
    URL.createObjectURL = function (obj) {
      var url = origCreate(obj);
      try { if (obj instanceof Blob) remember(url, obj); } catch (x) {}
      return url;
    };
    var origRevoke = URL.revokeObjectURL.bind(URL);
    URL.revokeObjectURL = function (url) {
      try { if (typeof url === 'string') forget(url); } catch (x) {}
      return origRevoke(url);
    };

    var active = null;

    function stopActive() {
      var cur = active;
      active = null;
      if (cur) forget(cur.url);
    }

    function ctrl(action, extra) {
      var m = { zcodeSave: action };
      if (extra) for (var k in extra) m[k] = extra[k];
      handler.postMessage(JSON.stringify(m));
    }

    // 页面的保存是程序触发的 a.click()；事件会冒泡到 document，捕获阶段在这里拦。
    document.addEventListener('click', function (e) {
      try {
        var t = e.target;
        var a = t && t.closest ? t.closest('a') : null;
        if (!a || !a.hasAttribute('download')) return;
        var href = String(a.getAttribute('href') || '');
        if (href.slice(0, 5) !== 'blob:') return;
        var blob = remembered.get(href);
        if (!blob) return;
        if (blob.size > MAX) return;
        if (!CAN_CHUNK) { ctrl('unsupported'); return; }
        e.preventDefault();
        if (e.stopImmediatePropagation) e.stopImmediatePropagation();
        else e.stopPropagation();
        if (active) return;
        active = { url: href, blob: blob, sending: false, sent: 0 };
        ctrl('begin', { name: String(a.getAttribute('download') || ''), size: blob.size, mime: blob.type || '' });
      } catch (x) {}
    }, true);

    handler.onmessage = function (ev) {
      try {
        var d = ev && ev.data;
        var msg = (typeof d === 'string') ? JSON.parse(d) : null;
        var action = msg && msg.zcodeSave;
        if (action === 'ready') {
          if (active && !active.sending) { active.sending = true; sendNext(); }
        } else if (action === 'ack') {
          if (active && active.sending) sendNext();
        } else if (action === 'done' || action === 'cancelled' || action === 'error') {
          stopActive();
        }
      } catch (x) {
        stopActive();
        try { ctrl('abort'); } catch (y) {}
      }
    };

    function sendNext() {
      var cur = active;
      if (!cur || !cur.sending) return;
      var offset = cur.sent;
      if (offset >= cur.blob.size) {
        cur.sending = false;
        ctrl('end');
        return;
      }
      var end = Math.min(offset + CHUNK, cur.blob.size);
      cur.blob.slice(offset, end).arrayBuffer().then(function (buf) {
        if (active !== cur || !cur.sending) return;
        cur.sent = end;
        handler.postMessage(buf);
      }, function () {
        stopActive();
        try { ctrl('abort'); } catch (y) {}
      });
    }
  } catch (x) {}
})();
""".trimIndent()
}
