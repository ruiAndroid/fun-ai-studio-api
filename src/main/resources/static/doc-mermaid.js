// /doc/** 页面 Mermaid 渲染器：把 ```mermaid``` code fence 变成真正的图
//
// commonmark 输出形态：
//   <pre><code class="language-mermaid">...</code></pre>
//
// 做法：
// 1) 替换为 <div class="mermaid">...</div>
// 2) 动态加载 mermaid（多 CDN 兜底）
// 3) mermaid.run() 渲染

(function () {
  function showBanner(msg) {
    try {
      var div = document.createElement("div");
      div.style.cssText =
        "position:fixed;z-index:9999;left:12px;right:12px;bottom:12px;" +
        "background:#fff3cd;border:1px solid #ffecb5;color:#664d03;" +
        "padding:10px 12px;border-radius:10px;font:14px/1.4 system-ui,-apple-system,Segoe UI,Roboto,Arial;";
      div.textContent = msg;
      document.body.appendChild(div);
    } catch (_) {}
  }

  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var s = document.createElement("script");
      s.src = src;
      s.defer = true;
      s.onload = function () {
        resolve();
      };
      s.onerror = function () {
        reject(new Error("failed to load script: " + src));
      };
      document.head.appendChild(s);
    });
  }

  function collectMermaidBlocks() {
    var nodes = document.querySelectorAll(
      "pre > code.language-mermaid, pre > code.lang-mermaid, pre > code.mermaid"
    );
    if (!nodes || !nodes.length) return [];
    var blocks = [];
    for (var i = 0; i < nodes.length; i++) {
      var code = nodes[i];
      var pre = code.parentElement;
      if (!pre || !pre.parentElement) continue;
      var txt = code.textContent || "";
      blocks.push({ pre: pre, txt: txt });
    }
    return blocks;
  }

  function replaceWithMermaidDivs(blocks) {
    if (!blocks || !blocks.length) return 0;
    var count = 0;
    for (var i = 0; i < blocks.length; i++) {
      var pre = blocks[i].pre;
      if (!pre || !pre.parentElement) continue;
      var div = document.createElement("div");
      div.className = "mermaid";
      div.textContent = blocks[i].txt || "";
      pre.parentElement.replaceChild(div, pre);
      count++;
    }
    return count;
  }

  function render() {
    if (!window.mermaid) return;
    window.mermaid.initialize({ startOnLoad: false });
    try {
      var p = window.mermaid.run({ querySelector: ".mermaid" });
      if (p && typeof p.catch === "function") {
        p.catch(function (e) {
          if (console && console.warn) console.warn("mermaid render failed", e);
          showBanner("Mermaid 渲染失败（请打开控制台查看详细错误）。");
        });
      }
    } catch (e) {
      if (console && console.warn) console.warn("mermaid render failed", e);
      showBanner("Mermaid 渲染失败（请打开控制台查看详细错误）。");
    }
  }

  function ensureMermaid() {
    if (window.mermaid) return Promise.resolve();

    // 多 CDN 兜底：不同网络环境可达性差异很大（尤其是内网/生产）
    var candidates = [
      // 国内常见可用 CDN（优先）
      "https://cdn.staticfile.net/mermaid/10.9.0/mermaid.min.js",
      "https://lib.baomitu.com/mermaid/10.9.0/mermaid.min.js",

      // 国际 CDN（兜底）
      "https://cdnjs.cloudflare.com/ajax/libs/mermaid/10.9.0/mermaid.min.js",
      "https://unpkg.com/mermaid@10/dist/mermaid.min.js",
      "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js",
    ];

    var p = Promise.reject(new Error("no mermaid source tried"));
    for (var i = 0; i < candidates.length; i++) {
      (function (src) {
        p = p.catch(function () {
          return loadScript(src);
        });
      })(candidates[i]);
    }
    return p;
  }

  function boot() {
    var blocks = collectMermaidBlocks();
    if (!blocks.length) return;
    ensureMermaid()
      .then(function () {
        // 只有 mermaid 成功加载才替换 code block，避免“渲染失败后变空白”
        replaceWithMermaidDivs(blocks);
        render();
      })
      .catch(function (e) {
        if (console && console.warn) console.warn("mermaid init failed", e);
        showBanner(
          "Mermaid 未能加载（网络/CDN 不可达），已回退为显示原始代码块。"
        );
      });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();


