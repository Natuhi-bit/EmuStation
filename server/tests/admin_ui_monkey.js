// 管理画面（/admin/）のモンキーテスト：ブラウザで、見えているボタン・入力欄・選択肢をでたらめに操作し続けて、
//   - JavaScript のエラーが起きない
//   - サーバーが 500 を返さない
//   - ファイル名などに埋め込んだ HTML が実行されない（XSS にならない）
//   - 最後に画面がまだ使える
// ことを確かめる。admin-ui-monkey.sh から呼ぶ。
//   node admin_ui_monkey.js <URL> <パスワード> <回数> <乱数の種> <アップロード用ファイルのフォルダ>
const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");

const [url, password, stepsArg, seedArg, uploadDir] = process.argv.slice(2);
const STEPS = Number(stepsArg || 300);
let seed = Number(seedArg || 1) >>> 0;

// 再現できる乱数（mulberry32）
function rand() {
  seed = (seed + 0x6d2b79f5) >>> 0;
  let t = seed;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const pick = (a) => a[Math.floor(rand() * a.length)];
const TEXTS = [
  "", "psp", "ps2", "SAVEDATA", "../x", "a b", "日本語", "😀", "x".repeat(300), "<img src=x onerror=window.__xss=1>",
  "\"'><script>window.__xss=1</script>", "*.db", "[", "tablet", "new-dev", "admin", "-1", "99999999999999999999",
];
const uploads = fs.readdirSync(uploadDir).map((f) => path.join(uploadDir, f)).filter((f) => fs.statSync(f).isFile());
// フォルダを選ぶ入力欄（webkitdirectory）用
const folderRoot = path.join(uploadDir, "..", "folders");
const folders = fs.readdirSync(folderRoot).map((f) => path.join(folderRoot, f));

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
  const problems = [];
  const log = [];
  page.on("pageerror", (e) => problems.push(`JavaScript のエラー: ${e.message}`));
  page.on("response", (r) => { if (r.status() >= 500) problems.push(`${r.status()} ${r.request().method()} ${r.url()}`); });
  page.on("dialog", (d) => (rand() < 0.6 ? d.accept() : d.dismiss()).catch(() => {}));

  async function login() {
    if (!(await page.locator("#password").isVisible().catch(() => false))) await page.goto(url, { waitUntil: "networkidle" });
    if (await page.locator("#password").isVisible()) {
      await page.fill("#password", password);
      await page.locator("#login-form button[type=submit], #login-form button").first().click();
      await page.waitForSelector("#app-view:not([hidden])", { timeout: 10000 });
    }
  }
  await login();

  const SELECTOR = "button, a[href], input, select, textarea, summary, [role=tab], tr, li";
  for (let i = 0; i < STEPS; i++) {
    try {
      if (rand() < 0.02) {
        log.push("reload");
        await page.reload({ waitUntil: "networkidle" });
        if (await page.locator("#password").isVisible()) await login();
        continue;
      }
      // ログアウトしたら、たいていはすぐログインし直す（でたらめなパスワードは 1 秒待たされるので）
      if (rand() < 0.7 && (await page.locator("#password").isVisible())) {
        log.push("login");
        await login();
        continue;
      }
      const all = await page.locator(SELECTOR).all();
      const visible = [];
      for (const el of all) if (await el.isVisible().catch(() => false)) visible.push(el);
      if (!visible.length) continue;
      let el = pick(visible);
      // ログアウトは常に見えていて当たりやすいので、たまにだけ押す
      if ((await el.getAttribute("id")) === "logout" && rand() < 0.85) el = pick(visible);
      const tag = await el.evaluate((e) => e.tagName.toLowerCase());
      const type = (await el.getAttribute("type")) || "";
      const desc = await el.evaluate((e) => `${e.tagName.toLowerCase()}#${e.id || ""}.${e.className || ""} "${(e.textContent || "").trim().slice(0, 30)}"`);
      if (tag === "input" && type === "file") {
        log.push(`file ${desc}`);
        if ((await el.getAttribute("webkitdirectory")) !== null) {
          await el.setInputFiles(pick(folders));
        } else {
          const files = [pick(uploads)];
          if (rand() < 0.3 && (await el.getAttribute("multiple")) !== null) files.push(pick(uploads));
          await el.setInputFiles(files);
        }
      } else if ((tag === "input" && !["checkbox", "radio", "submit", "button"].includes(type)) || tag === "textarea") {
        const t = pick(TEXTS);
        log.push(`fill ${desc} ${JSON.stringify(t).slice(0, 40)}`);
        await el.fill(t, { timeout: 1000 });
        if (rand() < 0.3) await el.press("Enter");
      } else if (tag === "select") {
        const opts = await el.locator("option").evaluateAll((os) => os.map((o) => o.value));
        log.push(`select ${desc}`);
        if (opts.length) await el.selectOption(pick(opts), { timeout: 1000 });
      } else {
        log.push(`click ${desc}`);
        await el.click({ timeout: 1000 });
      }
      await page.waitForTimeout(rand() < 0.2 ? 400 : 40);
    } catch (e) {
      // 操作の途中で要素が消えた・押せなかった（画面が変わった）などは、そのまま続ける
      if (!/Timeout|detached|not attached|not visible|not an <input>|outside of the viewport|intercepts pointer|Element is not|Target closed|Execution context/.test(e.message)) {
        problems.push(`操作で例外: ${e.message.split("\n")[0]}`);
      }
    }
    if (problems.length) break;
  }

  await page.waitForLoadState("networkidle").catch(() => {});
  if (await page.evaluate(() => window.__xss)) problems.push("埋め込んだ HTML が実行された（XSS）");
  // まだ使えること：読み直すと、ログイン画面か管理画面が出る
  await page.reload({ waitUntil: "networkidle" });
  const ok = (await page.locator("#app-view").isVisible()) || (await page.locator("#login-view").isVisible());
  if (!ok) problems.push("読み直しても画面が出ない");
  await page.screenshot({ path: path.join(uploadDir, "..", "admin-monkey-last.png"), fullPage: true });
  await browser.close();

  const kinds = {};
  for (const l of log) { const k = l.startsWith("fill") ? l.replace(/" .*/, '"') : l; kinds[k] = (kinds[k] || 0) + 1; }
  console.log(`操作 ${log.length} 回（種類 ${Object.keys(kinds).length}）`);
  if (process.env.MONKEY_VERBOSE) console.log(Object.entries(kinds).sort((a, b) => b[1] - a[1]).map(([k, n]) => `  ${n}\t${k}`).join("\n"));
  if (problems.length) {
    console.log("!! 問題:\n  " + problems.join("\n  "));
    console.log("直前の操作:\n  " + log.slice(-20).join("\n  "));
    process.exit(1);
  }
  console.log("OK");
})();
