"use strict";
const $ = id => document.getElementById(id);
let csrf = "";
let qrGeneration = 0;
let note = null;
let busyCount = 0;
let verificationKey = null;
let captchaFrame = null;
let captchaSession = null;
function clearVerification() {
  verificationKey = null;
  captchaSession = null;
  captchaFrame = null;
  $("verification").hidden = true;
  $("captchaContainer").replaceChildren();
}
function message(text, error = false) {
  $("message").textContent = text;
  $("message").classList.toggle("error", error);
}
async function api(path, body = {}) {
  const response = await fetch(path, {
    method: "POST", headers: {"Content-Type": "application/json", "X-Demo-Token": csrf},
    body: JSON.stringify(body), cache: "no-store"
  });
  const data = await response.json();
  if (!response.ok) {
    const e = data.error || {};
    const failure = new Error([e.message || "请求失败", e.retcode == null ? "" : `retcode: ${e.retcode}`, e.hint].filter(Boolean).join("\n"));
    failure.retcode = e.retcode;
    failure.verificationRequired = !!e.verification_required;
    throw failure;
  }
  return data;
}
async function run(action, mustRun = false) {
  if (busyCount && !mustRun) return;
  busyCount++;
  document.querySelectorAll("button").forEach(b => b.disabled = true);
  try { await action(); }
  catch (e) { message(e.message, true); }
  finally {
    busyCount--;
    if (!busyCount) document.querySelectorAll("button").forEach(b => b.disabled = false);
  }
}
function populateRoles(roles) {
  $("roles").replaceChildren();
  if (!roles.length) $("roles").add(new Option("未发现角色，请先登录并加载", ""));
  for (const r of roles) $("roles").add(new Option(`${r.game} · ${r.nickname} · ${r.uid} · ${r.region_name || r.server}`, r.key));
}
async function state() {
  const response = await fetch("/api/state", {cache: "no-store"});
  if (!response.ok) throw new Error("无法连接本地服务");
  const data = await response.json();
  csrf = data.csrf;
  $("loginState").textContent = data.authenticated
    ? `已获取网页 Cookie（尚不代表便笺查询成功）：${data.cookie_names.join(", ")}`
    : "未登录 · Cookie 仅存服务内存";
  populateRoles(data.roles);
}
function stopQR() {
  qrGeneration++;
  $("qrPanel").hidden = true;
  $("qr").removeAttribute("src");
  $("loginLink").removeAttribute("href");
}
async function loadRoles() {
  const data = await api("/api/roles");
  populateRoles(data.roles);
  message(`已加载 ${data.roles.length} 个绑定角色。请选择角色并查询。`);
}
async function pollQR(generation, until) {
  if (generation !== qrGeneration) return;
  try {
    const data = await api("/api/qr/poll");
    if (generation !== qrGeneration) return;
    if (data.status === "Confirmed") {
      stopQR();
      clearVerification();
      note = null;
      $("result").hidden = true;
      $("raw").textContent = "";
      await state();
      message("网页登录成功，正在加载绑定角色…");
      await loadRoles();
      return;
    }
    $("qrStatus").textContent = data.status === "Scanned" ? "已扫描，请在手机上确认登录" : "等待米游社扫码…";
    if (Date.now() < until) setTimeout(() => pollQR(generation, until), 3000);
    else { stopQR(); message("扫码等待已结束，请重新生成二维码。"); }
  } catch (e) {
    if (generation !== qrGeneration) return;
    stopQR();
    message(e.message, true);
  }
}
$("login").onclick = () => run(async () => {
  stopQR();
  const data = await api("/api/qr/create");
  $("qr").src = `/api/qr.svg?t=${Date.now()}`;
  $("loginLink").href = data.login_url;
  $("qrStatus").textContent = "请用米游社扫描并手动确认登录";
  $("qrPanel").hidden = false;
  message("二维码已生成。登录授权请由你在官方页面/App 中确认。");
  const generation = qrGeneration;
  setTimeout(() => pollQR(generation, Date.now() + 180000), 3000);
});
$("logout").onclick = () => run(async () => {
  stopQR();
  clearVerification();
  const data = await api("/api/logout");
  note = null;
  $("result").hidden = true;
  $("raw").textContent = "";
  await state();
  message(data.message);
});
$("cookieForm").onsubmit = event => {
  event.preventDefault();
  run(async () => {
    stopQR();
    clearVerification();
    const cookie = $("cookie").value;
    $("cookie").value = "";
    await api("/api/cookie", {cookie});
    note = null;
    $("result").hidden = true;
    $("raw").textContent = "";
    await state();
    await loadRoles();
  });
};
$("loadRoles").onclick = () => run(loadRoles);
const value = v => v == null ? "未知" : String(v);
const pair = o => `${value(o.current)} / ${value(o.max)}`;
function clock(seconds) {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 3600)} 小时 ${Math.floor(s % 3600 / 60)} 分 ${s % 60} 秒`;
}
function countdown() {
  if (!note) return;
  const {stamina, fetched_at} = note.summary;
  if (stamina.recovery_seconds == null) $("recovery").textContent = "恢复时间未知（检查原始返回）";
  else if (stamina.recovery_seconds === 0) $("recovery").textContent = "官方返回恢复剩余时间为 0";
  else {
    const remain = stamina.recovery_seconds - (Date.now() / 1000 - fetched_at);
    $("recovery").textContent = remain > 0 ? `预计 ${clock(remain)} 后回满（本地倒计时）` : "预计已回满，请重新查询确认";
  }
}
async function queryNotes(key) {
  if (!key) throw new Error("请先选择绑定角色");
  message("正在查询官方网页版实时便笺…");
  note = null;
  $("result").hidden = true;
  $("raw").textContent = "";
  clearVerification();
  try { note = await api("/api/notes", {key}); }
  catch (e) {
    if (e.verificationRequired) {
      verificationKey = key;
      $("verification").hidden = false;
    }
    throw e;
  }
  const s = note.summary;
  $("resultTitle").textContent = `${note.role.game} · ${note.role.nickname} · ${note.role.uid}`;
  $("stamina").textContent = pair(s.stamina);
  $("dailyLabel").textContent = s.daily.label;
  $("daily").textContent = pair(s.daily);
  if (typeof s.daily.extra_reward_received === "boolean") {
    $("reward").textContent = s.daily.extra_reward_received ? "额外委托奖励：已领取" : "额外委托奖励：未领取";
  } else $("reward").textContent = "奖励领取状态：当前摘要无法确认，请查看原始返回";
  $("fetched").textContent = `获取时间：${new Date(s.fetched_at * 1000).toLocaleString()} · ${note.cached ? "60 秒内本地缓存" : "本次官方实时请求"}（仍可能存在官方同步延迟）`;
  $("raw").textContent = JSON.stringify(note.raw, null, 2);
  $("result").hidden = false;
  countdown();
  message("网页版战绩实时便笺查询成功。");
}
$("query").onclick = () => run(() => queryNotes($("roles").value));
$("roles").onchange = clearVerification;
$("verify").onclick = () => run(async () => {
  if (!verificationKey || $("roles").value !== verificationKey) throw new Error("请先查询当前角色，确认是否需要验证。");
  captchaSession = null;
  captchaFrame = null;
  $("captchaContainer").replaceChildren();
  message("正在向官方创建当前请求的验证码…");
  captchaSession = await api("/api/verification/create", {key: verificationKey});
  captchaFrame = document.createElement("iframe");
  captchaFrame.title = "极验手动人机验证（隔离页面）";
  captchaFrame.setAttribute("sandbox", "allow-scripts");
  captchaFrame.setAttribute("referrerpolicy", "no-referrer");
  captchaFrame.src = "/captcha.html";
  $("captchaContainer").replaceChildren(captchaFrame);
  message("请在下方隔离页面手动完成官方人机验证。");
});
window.addEventListener("message", async event => {
  if (!captchaFrame || event.source !== captchaFrame.contentWindow || event.origin !== "null" || event.data?.source !== "mihoyo-captcha") return;
  const data = event.data;
  if (data.type === "ready" && captchaSession) {
    captchaFrame.contentWindow.postMessage({type: "start-captcha", mmt: captchaSession}, "*");
  } else if (data.type === "success" && captchaSession && data.id === captchaSession.id) {
    // Copy and consume the one-shot session before awaiting server verification.
    const id = captchaSession.id;
    const key = verificationKey;
    captchaSession = null;
    await run(async () => {
      message("滑块已完成，正在向官方提交验证结果…");
      await api("/api/verification/submit", {id, validation: data.validation});
      clearVerification();
      message("官方验证通过，正在重查便笺…");
      await queryNotes(key);
    }, true);
  } else if (data.type === "error") message("极验组件加载或验证失败，请重新点击手动验证。", true);
  else if (data.type === "closed") message("验证码已关闭，可重新点击手动验证。");
});
setInterval(countdown, 1000);
state().then(() => message("本地服务已连接。请登录后查询真实便笺数据。")).catch(e => message(e.message, true));
