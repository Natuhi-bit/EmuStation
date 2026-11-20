// emusync 管理画面。ページは /emusync/admin/ にあるので、API は相対パス api/... で呼ぶ
"use strict";

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

// ---------------------------------------------------------------- 共通
class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

async function api(path, { method = "GET", body, form } = {}) {
  const headers = { "X-Emusync-Admin": "1" };
  let payload;
  if (form) payload = form;
  else if (body !== undefined) { headers["Content-Type"] = "application/json"; payload = JSON.stringify(body); }
  const res = await fetch("api/" + path, { method, headers, body: payload, credentials: "same-origin" });
  if (res.status === 401 && path !== "login") { showLogin(); throw new ApiError(401, "ログインしてください"); }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* HTML のエラーページなど */ }
  if (!res.ok) {
    let msg = data && data.detail;
    if (msg && typeof msg === "object") msg = msg.message || JSON.stringify(msg);
    if (res.status === 413 && !msg) msg = "大きすぎます（Nginx の client_max_body_size を確認してください）";
    throw new ApiError(res.status, msg || `HTTP ${res.status}`);
  }
  return data;
}

/** パスの各部分をエンコードし、/ は残す */
const encPath = (p) => p.split("/").map(encodeURIComponent).join("/");

function fmtSize(n) {
  if (n == null) return "";
  if (n < 1024) return `${n} B`;
  if (n < 1024 ** 2) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(1)} MB`;
  return `${(n / 1024 ** 3).toFixed(2)} GB`;
}
const fmtTime = (sec) => (sec ? new Date(sec * 1000).toLocaleString("ja-JP") : "");

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v);
  }
  for (const c of children) e.append(c instanceof Node ? c : document.createTextNode(c ?? ""));
  return e;
}

let toastTimer;
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 4000);
}

function showError(sel, e) {
  const p = $(sel);
  p.textContent = e ? e.message : "";
  p.hidden = !e;
}

// ---------------------------------------------------------------- ログイン
let uploadReady = true;

async function boot() {
  const s = await api("session");
  uploadReady = s.upload_ready !== false;
  if (s.logged_in) showApp();
  else showLogin(!s.configured);
}

function showLogin(notConfigured = false) {
  $("#app-view").hidden = true;
  $("#logout").hidden = true;
  $("#login-view").hidden = false;
  $("#not-configured").hidden = !notConfigured;
  $("#password").focus();
}

$("#login-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  showError("#login-error", null);
  try {
    await api("login", { method: "POST", body: { password: $("#password").value } });
    $("#password").value = "";
    showApp();
  } catch (e) {
    showError("#login-error", e);
    if (e.status === 409) $("#not-configured").hidden = false;
  }
});

$("#logout").addEventListener("click", async () => {
  await api("logout", { method: "POST" }).catch(() => {});
  showLogin();
});

function showApp() {
  $("#login-view").hidden = true;
  $("#app-view").hidden = false;
  $("#logout").hidden = false;
  openTab(location.hash.slice(1) || "overview");
}

// ---------------------------------------------------------------- タブ
const loaders = {};
function openTab(name) {
  if (!loaders[name]) name = "overview";
  $$(".tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
  $$("[data-panel]").forEach((p) => (p.hidden = p.dataset.panel !== name));
  history.replaceState(null, "", "#" + name);
  loaders[name]().catch((e) => e.status !== 401 && toast(e.message));
}
$$(".tabs button").forEach((b) => b.addEventListener("click", () => openTab(b.dataset.tab)));

// ---------------------------------------------------------------- 概要
let targetNames = [];
const PRESETS = ["psp", "vita", "ps2", "ps3"];

async function loadTargets() {
  const o = await api("overview");
  targetNames = o.targets.map((t) => t.name);
  const names = [...new Set([...PRESETS, ...targetNames])];
  $("#target-names").replaceChildren(...names.map((n) => el("option", { value: n })));
  return o;
}

loaders.overview = async () => {
  const o = await loadTargets();
  const files = o.targets.reduce((a, t) => a + t.files, 0);
  const stat = (v, label) => el("div", { class: "stat" }, el("b", {}, String(v)), el("span", {}, label));
  $("#stats").replaceChildren(
    stat(o.targets.length, "ターゲット"), stat(files, "ファイル"), stat(o.versions, "版（履歴を含む）"),
    stat(fmtSize(o.stored_bytes), "使用容量"), stat(o.devices, "端末"), stat(o.locks, "プレイ中ロック"),
  );
  $("#targets").replaceChildren(...o.targets.map((t) =>
    el("tr", { class: "click", onclick: () => { fileTarget = t.name; openTab("files"); } },
      el("td", {}, t.name), el("td", { class: "num" }, String(t.files)),
      el("td", { class: "num" }, fmtSize(t.size)), el("td", {}, fmtTime(t.updated_at)))));
  $("#targets-empty").hidden = o.targets.length > 0;
};

// ---------------------------------------------------------------- ファイル
let fileTarget = null;
let fileRows = [];

loaders.files = async () => {
  await loadTargets();
  const sel = $("#file-target");
  sel.replaceChildren(...targetNames.map((n) => el("option", { value: n }, n)));
  if (!fileTarget || !targetNames.includes(fileTarget)) fileTarget = targetNames[0] || null;
  sel.value = fileTarget || "";
  $("#history").hidden = true;
  await loadFiles();
};

async function loadFiles() {
  fileRows = fileTarget ? (await api("files?target=" + encodeURIComponent(fileTarget))).files : [];
  renderFiles();
}

function renderFiles() {
  const q = $("#file-filter").value.trim().toLowerCase();
  const rows = fileRows.filter((f) => !q || f.rel.toLowerCase().includes(q));
  $("#files").replaceChildren(...rows.map((f) =>
    el("tr", { class: "click", "data-path": f.path, onclick: () => showHistory(f.path) },
      el("td", {}, el("code", {}, f.rel)), el("td", { class: "num" }, "v" + f.version),
      el("td", { class: "num" }, fmtSize(f.size)), el("td", {}, fmtTime(f.updated_at)), el("td", {}, f.device))));
  $("#files-empty").hidden = rows.length > 0;
}

$("#file-target").addEventListener("change", (e) => { fileTarget = e.target.value; $("#history").hidden = true; loadFiles(); });
$("#file-filter").addEventListener("input", renderFiles);

async function showHistory(path) {
  $$("#files tr").forEach((tr) => tr.classList.toggle("selected", tr.dataset.path === path));
  const h = await api("history/" + encPath(path));
  const latest = h.versions.length ? h.versions[0].version : null;
  $("#history-path").textContent = path;
  $("#versions").replaceChildren(...h.versions.map((v) =>
    el("tr", {},
      el("td", {}, "v" + v.version + (v.version === latest ? "（最新）" : "")),
      el("td", {}, fmtTime(v.created_at)), el("td", { class: "num" }, fmtSize(v.size)),
      el("td", {}, v.device), el("td", {}, el("code", {}, v.sha256.slice(0, 12))),
      el("td", { class: "num" },
        el("a", { href: `api/download/${encPath(path)}?version=${v.version}` }, "ダウンロード"), " ",
        v.version === latest ? "" : el("button", { class: "ghost", onclick: () => restore(path, v.version, latest) }, "この版に戻す")))));
  $("#history").hidden = false;
  $("#history").scrollIntoView({ behavior: "smooth", block: "nearest" });
}

async function restore(path, version, latest) {
  if (!confirm(`v${version} の内容を、新しい版（v${latest + 1}）として最新にします。今までの版は履歴に残ります。\n各端末は次の同期で取得します（手元も変えていた端末では競合になります）。`)) return;
  try {
    const r = await api("restore/" + encPath(path), { method: "POST", body: { version } });
    toast(`v${version} の内容を v${r.version} として最新にしました`);
    await loadFiles();
    await showHistory(path);
  } catch (e) { toast(e.message); }
}

// ---------------------------------------------------------------- インポート
const DEFAULT_EXCLUDES = ["desktop.ini", "Thumbs.db", ".DS_Store", "*.part", ".nextcloudsync.log", ".sync_*.db*"];
let imp = null; // { id, top }

loaders.import = async () => {
  await loadTargets();
  $("#imp-not-ready").hidden = uploadReady;
  if (!$("#imp-excludes").value) $("#imp-excludes").value = DEFAULT_EXCLUDES.join("\n");
};

function chosenFiles() {
  const folder = $("#imp-folder").files;
  const zip = $("#imp-zip").files;
  return { folder: folder.length ? [...folder] : null, zip: zip.length ? zip[0] : null };
}

function updateChosen() {
  const { folder, zip } = chosenFiles();
  const parts = [];
  if (folder) parts.push(`フォルダ：${folder.length} 個のファイル（${fmtSize(folder.reduce((a, f) => a + f.size, 0))}）`);
  if (zip) parts.push(`zip：${zip.name}（${fmtSize(zip.size)}）`);
  $("#imp-chosen").textContent = parts.join(" / ");
  $("#imp-upload").disabled = !(folder || zip);
}
$("#imp-folder").addEventListener("change", () => { $("#imp-zip").value = ""; updateChosen(); });
$("#imp-zip").addEventListener("change", () => { $("#imp-folder").value = ""; updateChosen(); });

/** アップロードの進み具合を出すため、fetch ではなく XMLHttpRequest を使う */
function uploadForm(form, totalBytes) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const started = Date.now();
    let sent = 0;
    xhr.open("POST", "api/imports");
    xhr.setRequestHeader("X-Emusync-Admin", "1");
    xhr.upload.onprogress = (e) => {
      sent = e.loaded;
      if (e.lengthComputable) $("#imp-progress").value = (e.loaded / e.total) * 100;
    };
    xhr.onload = () => {
      let data = null;
      try { data = JSON.parse(xhr.responseText); } catch { /* */ }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data);
      else {
        let msg = data && data.detail;
        if (xhr.status === 413 && !msg) msg = "大きすぎます（Nginx の client_max_body_size を確認してください）";
        if (xhr.status === 401) showLogin();
        reject(new ApiError(xhr.status, msg || `HTTP ${xhr.status}`));
      }
    };
    // 通信が途中で切れた。どこまで送れたかで、原因の見当がつく
    //   0 B：選んだファイルを読めなかった（エミュが使用中で書き換わった など）か、最初からつながらない
    //   途中まで：VPN（MTU）やモバイル回線で、大きな送信が止まった など
    xhr.onerror = () => reject(new ApiError(0,
      `送信が途中で切れました（${fmtSize(totalBytes)} 中 ${fmtSize(sent)} を送信、${Math.round((Date.now() - started) / 1000)} 秒後）。` +
      (sent === 0
        ? "選んだファイルを読めなかった可能性があります（エミュが使用中だと書き換わって失敗します）。エミュを終了してから選び直すか、zip にして選んでください。"
        : "通信が切れた可能性があります。自宅の Wi-Fi で試すか、zip にして小分けにしてください。")));
    xhr.send(form);
  });
}

$("#imp-upload").addEventListener("click", async () => {
  showError("#imp-error", null);
  const target = $("#imp-target").value.trim();
  if (!/^[A-Za-z0-9._-]+$/.test(target)) { showError("#imp-error", new Error("ターゲット名を入れてください（英数字と . _ - のみ）")); return; }
  const { folder, zip } = chosenFiles();
  const totalBytes = zip ? zip.size : folder.reduce((a, f) => a + f.size, 0);
  const form = new FormData();
  if (zip) form.append("zip", zip);
  else folder.forEach((f) => { form.append("files", f, f.name); form.append("paths", f.webkitRelativePath || f.name); });
  if (imp) await api(`imports/${imp.id}`, { method: "DELETE" }).catch(() => {});
  $("#imp-upload").disabled = true;
  $("#imp-progress").hidden = false;
  $("#imp-progress").value = 0;
  try {
    const r = await uploadForm(form, totalBytes);
    imp = { id: r.id, top: r.top };
    $("#imp-top").textContent = r.top || "";
    $("#imp-strip-label").hidden = !r.top;
    $("#imp-strip").checked = !!r.top;
    $("#imp-prefix").value = "";
    $("#imp-updates").checked = false;
    $("#imp-result").hidden = true;
    $("#imp-step2").hidden = false;
    await previewImport();
    $("#imp-step2").scrollIntoView({ behavior: "smooth" });
  } catch (e) {
    showError("#imp-error", e);
  } finally {
    $("#imp-upload").disabled = false;
    $("#imp-progress").hidden = true;
  }
});

function importOptions() {
  return {
    target: $("#imp-target").value.trim(),
    prefix: $("#imp-prefix").value.trim(),
    strip_top: $("#imp-strip").checked,
    excludes: $("#imp-excludes").value.split("\n").map((s) => s.trim()).filter(Boolean),
    include_updates: $("#imp-updates").checked,
  };
}

const STATUS = {
  new: "新規", same: "同じ（取り込まない）", update: "上書き（新しい版）", skip_update: "内容が違う（取り込まない）",
  excluded: "除外", invalid: "取り込めない",
};

let previewTimer;
function schedulePreview() { clearTimeout(previewTimer); previewTimer = setTimeout(() => previewImport().catch((e) => toast(e.message)), 300); }
["#imp-strip", "#imp-updates"].forEach((s) => $(s).addEventListener("change", schedulePreview));
["#imp-prefix", "#imp-excludes", "#imp-target"].forEach((s) => $(s).addEventListener("input", () => imp && schedulePreview()));

async function previewImport() {
  if (!imp) return;
  const p = await api(`imports/${imp.id}/preview`, { method: "POST", body: importOptions() });
  const c = p.counts;
  $("#imp-counts").replaceChildren(...Object.keys(STATUS).filter((k) => c[k]).map((k) =>
    el("span", { class: "chip " + k }, `${STATUS[k]} ${c[k]}`)));
  $("#imp-items").replaceChildren(...p.items.map((i) =>
    el("tr", {},
      el("td", {}, el("span", { class: "chip " + i.status }, STATUS[i.status] + (i.version ? `（今 v${i.version}）` : "")),
        i.reason ? el("div", { class: "muted" }, i.reason) : ""),
      el("td", {}, el("code", {}, i.path)), el("td", { class: "num" }, fmtSize(i.size)))));
  const n = (c.new || 0) + (c.update || 0);
  $("#imp-commit").disabled = n === 0;
  $("#imp-commit").textContent = n ? `${n} 件を取り込む` : "取り込むファイルがありません";
}

$("#imp-commit").addEventListener("click", async () => {
  const o = importOptions();
  if (o.include_updates && !confirm("サーバーと内容が違う既存のファイルも、新しい版として取り込みます。よろしいですか？（今の版は履歴に残ります）")) return;
  $("#imp-commit").disabled = true;
  try {
    const r = await api(`imports/${imp.id}/commit`, { method: "POST", body: o });
    imp = null;
    $("#imp-result").textContent = `ターゲット ${r.target} に、新規 ${r.imported.new} 件・上書き ${r.imported.update} 件を取り込みました。各端末は次の同期で取得します。`;
    $("#imp-result").hidden = false;
    $("#imp-items").replaceChildren();
    $("#imp-counts").replaceChildren();
    $("#imp-folder").value = ""; $("#imp-zip").value = ""; updateChosen();
    toast("取り込みました");
  } catch (e) {
    toast(e.message);
    $("#imp-commit").disabled = false;
  }
});

$("#imp-cancel").addEventListener("click", async () => {
  if (imp) await api(`imports/${imp.id}`, { method: "DELETE" }).catch(() => {});
  imp = null;
  $("#imp-step2").hidden = true;
});

// ---------------------------------------------------------------- 端末
loaders.devices = async () => {
  const d = await api("devices");
  $("#devices").replaceChildren(...d.devices.map((x) =>
    el("tr", {}, el("td", {}, x.name), el("td", {}, fmtTime(x.created_at)),
      el("td", { class: "num" }, el("button", { class: "danger", onclick: () => removeDevice(x.name) }, "削除")))));
};

async function removeDevice(name) {
  if (!confirm(`端末 ${name} を削除しますか？ この端末のトークンは使えなくなります（サーバー上のファイルは消えません）。`)) return;
  try { await api("devices/" + encodeURIComponent(name), { method: "DELETE" }); toast(`${name} を削除しました`); loaders.devices(); }
  catch (e) { toast(e.message); }
}

$("#device-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  showError("#device-error", null);
  try {
    const r = await api("devices", { method: "POST", body: { name: $("#device-name").value.trim() } });
    $("#token-device").textContent = r.name;
    $("#token").textContent = r.token;
    $("#token-box").hidden = false;
    $("#device-name").value = "";
    loaders.devices();
  } catch (e) { showError("#device-error", e); }
});

$("#token-copy").addEventListener("click", async () => {
  try { await navigator.clipboard.writeText($("#token").textContent); toast("コピーしました"); }
  catch { toast("コピーできませんでした。選択してコピーしてください"); }
});

// ---------------------------------------------------------------- ロック
loaders.locks = async () => {
  const l = await api("locks");
  $("#locks").replaceChildren(...l.locks.map((x) =>
    el("tr", {}, el("td", {}, x.target), el("td", {}, x.device), el("td", {}, fmtTime(x.expires_at)),
      el("td", { class: "num" }, el("button", { class: "danger", onclick: () => releaseLock(x) }, "解除")))));
  $("#locks-empty").hidden = l.locks.length > 0;
};

async function releaseLock(x) {
  if (!confirm(`${x.target} の ${x.device} のロックを解除しますか？ ${x.device} がまだ遊んでいる場合、ほかの端末と競合するおそれがあります。`)) return;
  try { await api("locks/" + encodeURIComponent(x.target), { method: "DELETE" }); toast("解除しました"); loaders.locks(); }
  catch (e) { toast(e.message); }
}

boot().catch((e) => { document.body.prepend(el("p", { class: "error" }, "読み込めませんでした: " + e.message)); });
