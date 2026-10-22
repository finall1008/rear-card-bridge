"use strict";
// This page is sandboxed without allow-same-origin. It receives only ephemeral
// CAPTCHA parameters, never a Cookie, account identity or local API token.
let started = false;
let expectedParent = new URL(location.href).origin;
function tell(type, extra = {}) {
  parent.postMessage({source: "mihoyo-captcha", type, ...extra}, expectedParent);
}
window.addEventListener("message", event => {
  if (event.source !== parent || event.origin !== expectedParent || started || event.data?.type !== "start-captcha") return;
  started = true;
  const mmt = event.data.mmt;
  if (!mmt || typeof mmt.gt !== "string" || typeof mmt.challenge !== "string") return;
  const script = document.createElement("script");
  script.src = "https://static.geetest.com/static/js/gt.0.5.0.js";
  script.onerror = () => tell("error", {message: "无法加载极验官方组件，请检查网络。"});
  script.onload = () => {
    if (typeof window.initGeetest !== "function") return tell("error", {message: "验证码组件初始化失败。"});
    window.initGeetest({gt: mmt.gt, challenge: mmt.challenge, new_captcha: mmt.new_captcha,
      offline: !mmt.success, product: "float", https: true, lang: "zh-cn"}, captcha => {
      captcha.appendTo("#captcha");
      captcha.onReady(() => { document.getElementById("status").textContent = "请手动点击并完成人机验证。"; });
      captcha.onSuccess(() => {
        const validation = captcha.getValidate();
        if (validation) {
          document.getElementById("status").textContent = "验证结果已提交，等待官方确认…";
          tell("success", {id: mmt.id, validation});
        }
      });
      captcha.onError(() => tell("error", {message: "验证码组件发生错误，请重新创建。"}));
      captcha.onClose(() => tell("closed"));
    });
  };
  document.head.appendChild(script);
});
tell("ready");
