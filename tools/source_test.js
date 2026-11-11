#!/usr/bin/env node
/*
 * 书源实测：用真实网络把一个书源文件导入 App 页面，按顺序走「搜索 → 详情 → 目录 → 第一章」，打印每一步拿到了什么。
 *
 *   node tools/source_test.js <书源地址或文件> [关键词] [最多测几个源]
 *
 * 书源多于 12 个时先「筛选」：全部启用搜一次，只对搜到这本书的源继续测详情、目录、正文。
 * 全部通过的书源原文写到 tools/out/passed.json。
 *
 * 在 GitHub Actions 里由「书源实测」工作流运行（.github/workflows/test-source.yml），截图存到 tools/out/。
 * 这里用的是网页预览的请求方式（浏览器 fetch，关掉跨域检查来近似安卓外壳）；webView 页面和 AES 这类只在安装版里有的能力会跳过。
 */
const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");

const [, , SRC, KEY_ARG, MAX_ARG] = process.argv;
if (!SRC) { console.error("用法: node tools/source_test.js <书源地址或文件> [关键词] [最多测几个源]"); process.exit(2); }
const PAGE = path.resolve(__dirname, "../app/src/main/assets/index.html");
const OUT = path.resolve(__dirname, "out");
const MAX = +MAX_ARG || 10;
fs.mkdirSync(OUT, { recursive: true });
const summary = [];
const say = (...a) => { const t = a.join(" "); console.log(t); summary.push(t); };

(async () => {
  // 1. the source file
  let text;
  if (/^https?:/.test(SRC)) {
    const r = await fetch(SRC, { headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36" } });
    text = await r.text();
    say(`书源文件：${SRC}（HTTP ${r.status}，${text.length} 字）`);
  } else text = fs.readFileSync(SRC, "utf8");
  const file = path.join(OUT, "source.json");
  fs.writeFileSync(file, text);
  let raw = [];
  try { raw = [].concat(JSON.parse(text)); } catch (_) { console.log("\n----- 书源原文（前 3000 字）-----\n" + text.slice(0, 3000)); }
  const screen = raw.length > 12;
  if (!screen) for (const o of raw) {   // the parts that build requests, in full
    console.log(`\n----- ${o.bookSourceName} -----`);
    for (const k of ["bookSourceUrl", "header", "searchUrl", "jsLib", "loginUrl", "ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent"])
      if (o[k]) console.log(`${k}: ${typeof o[k] === "string" ? o[k] : JSON.stringify(o[k])}`.slice(0, 4000));
  }

  const browser = await chromium.launch({ args: ["--allow-file-access-from-files", "--disable-web-security"] });
  const page = await browser.newPage({ viewport: { width: 400, height: 860 } });
  await page.emulateMedia({ reducedMotion: "reduce" });
  const errors = []; page.on("pageerror", e => errors.push(e.message));
  await page.route(/fonts\.(googleapis|gstatic)\.com/, r => r.abort());   // not needed for the test
  // every request a source makes, for the log (pictures left out)
  let net = [];
  page.on("response", r => { const q = r.request(); if (!/^https?:/.test(q.url()) || q.resourceType() === "image") return;
    net.push(`${q.method()} ${q.url().slice(0, 200)} → ${r.status()}${q.postData() ? "  body=" + q.postData().slice(0, 120) : ""}`); });
  page.on("requestfailed", q => { if (/^https?:/.test(q.url()) && q.resourceType() !== "image") net.push(`${q.method()} ${q.url().slice(0, 200)} → 失败 ${q.failure()?.errorText || ""}`); });
  await page.goto("file://" + PAGE);
  await page.evaluate(() => localStorage.setItem("shelf-sources", "[]")); await page.reload(); await page.waitForTimeout(500);

  // 2. import exactly as a user would
  await page.setInputFiles("#srcFile", file); await page.waitForTimeout(1500);
  say("导入：" + await page.evaluate(() => [...document.querySelectorAll(".toast,[role=status]")].map(e => e.textContent.trim()).filter(t => t && t !== "撤销").join(" | ")));
  const list = await page.evaluate(() => JSON.parse(localStorage.getItem("shelf-sources") || "[]").map(s => ({ id: s.id, name: s.name, url: s.url, type: s.type, rule: !!s.search, check: s.search?.rule?.checkKeyWord || "" })));
  if (!list.length) { say("❌ 没有导入任何书源"); return finish(1); }

  const enable = ids => page.evaluate(ids => { const l = JSON.parse(localStorage.getItem("shelf-sources")); l.forEach(s => s.on = ids.includes(s.id)); localStorage.setItem("shelf-sources", JSON.stringify(l)); }, ids);
  const search = async (key, wait) => {
    await page.goto("file://" + PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => document.getElementById("libSearch").dispatchEvent(new PointerEvent("pointerdown", { bubbles: true })));
    await page.fill("#sxInput", key); await page.evaluate(() => document.getElementById("sxForm").requestSubmit());
    await page.waitForFunction(() => /已返回/.test(document.querySelector(".sx-stat")?.textContent || "") && !/正在/.test(document.querySelector(".sx-stat").textContent), null, { timeout: wait }).catch(() => {});
    await page.waitForTimeout(1500);
  };
  const rows = () => page.evaluate(() => [...document.querySelectorAll("#sxBody .sx-r")].filter(r => r.querySelector(".sx-tag.live")).map(r => ({
    t: r.querySelector(".sx-i b").textContent, meta: r.querySelector(".sx-i small").textContent, src: r.querySelector(".sx-tag.live").textContent.trim(),
    cover: !!r.querySelector("img")?.naturalWidth })));

  // 3. many sources: one search over all of them first
  let todo = list.filter(s => s.rule && s.type !== "漫画");
  if (screen) {
    const key = KEY_ARG || "斗罗大陆";
    say(`\n筛选：${todo.length} 个有搜索规则的小说源一起搜「${key}」…`);
    await enable(todo.map(s => s.id));
    await search(key, 25 * 60 * 1000);
    const found = await rows();
    const by = {};
    for (const r of found) if (r.t.includes(key)) (by[r.src] = by[r.src] || []).push(r.t);
    say(`搜到「${key}」的源 ${Object.keys(by).length} 个：${Object.keys(by).join("、")}`);
    console.log("搜索说明：" + await page.evaluate(() => (document.querySelector("#sxBody .gsx-note")?.innerText || "").slice(0, 3000)));
    todo = todo.filter(s => by[s.name]);
  }

  let bad = 0; const passed = [];
  for (const src of todo.slice(0, MAX)) {
    const key = KEY_ARG || src.check || "斗罗大陆";
    say(`\n## ${src.name}（${src.type}）· 搜「${key}」`);
    if (!src.rule) { say("  ❌ 没有搜索规则"); bad++; continue; }
    await enable([src.id]);
    net = [];
    await search(key, 60000);
    if (!screen) console.log("  请求：\n    " + net.slice(0, 12).join("\n    "));
    const shot = n => page.screenshot({ path: path.join(OUT, `${src.name.replace(/[\\/:*?"<>|\s]/g, "_")}-${n}.png`) }).catch(() => {});
    await shot("1-search");
    const hits = await rows();
    if (!hits.length) {
      bad++;
      say("  ❌ 搜索没有结果：" + await page.evaluate(() => (document.querySelector(".sx-none")?.innerText || "").replace(/\n+/g, " ")));
      const diag = await page.evaluate(() => [...document.querySelectorAll(".sx-diag")].map(d => { d.open = true; return d.innerText.replace(/\n+/g, " | "); }).join("\n    "));
      if (diag) say("    " + diag.slice(0, 700));
      continue;
    }
    const pick = Math.max(0, hits.findIndex(h => h.t === key));
    say(`  ✅ 搜索 ${hits.length} 本：` + hits.slice(0, 4).map(h => `${h.t}（${h.meta}${h.cover ? "，有封面" : "，封面没出来"}）`).join("、"));
    await page.evaluate(i => [...document.querySelectorAll("#sxBody .sx-r")].filter(r => r.querySelector(".sx-tag.live"))[i].querySelector(".sx-open").click(), pick);
    await page.waitForFunction(() => document.querySelector(".ddet .dt-src") && !/正在/.test(document.querySelector(".ddet .dt-src").textContent), null, { timeout: 90000 }).catch(() => {});
    await page.waitForTimeout(1200); await shot("2-detail");
    const det = await page.evaluate(() => { const d = document.querySelector(".ddet"); return d ? { stats: d.querySelector(".stats")?.innerText.replace(/\s+/g, " "), src: d.querySelector(".dt-src")?.textContent, syn: d.querySelector(".syn")?.textContent.slice(0, 60) } : null; });
    if (!det || /没能|正在/.test(det.src || "")) { bad++; say("  ❌ 详情 / 目录：" + (det?.src || "详情页没打开")); continue; }
    say(`  ✅ 详情：${det.stats} · ${det.src} · 简介「${det.syn}」`);
    await page.evaluate(() => document.querySelector(".ddet [data-go2=toc]").click()); await page.waitForTimeout(800); await shot("3-toc");
    const toc = await page.evaluate(() => [...document.querySelectorAll("#tocList .chap .ct")].map(e => e.textContent.trim()));
    say(`  ✅ 目录 ${toc.length} 章：${toc.slice(0, 3).join(" / ")}${toc.length > 3 ? " …… " + toc.slice(-1) : ""}`);
    await page.evaluate(() => document.querySelector("#tocList .chap")?.click());
    await page.waitForFunction(() => document.getElementById("reader").classList.contains("on") && !document.querySelector("#rdText .rd-wait"), null, { timeout: 60000 }).catch(() => {});
    await page.waitForTimeout(2500); await shot("4-read");
    const rd = await page.evaluate(() => ({ imgs: [...document.querySelectorAll("#rdText img.rd-pg")].map(i => !!i.naturalWidth), text: document.getElementById("rdText").innerText.replace(/\n+/g, " / ") }));
    if (rd.imgs.length) say(`  ${rd.imgs.some(Boolean) ? "✅" : "⚠️"} 正文：${rd.imgs.length} 张图，已显示 ${rd.imgs.filter(Boolean).length} 张`);
    else if (/没能加载|没有正文|没有解析|正在从/.test(rd.text)) { bad++; say("  ❌ 正文：" + rd.text.slice(0, 200)); }
    else {
      say(`  ✅ 正文（${rd.text.length} 字）：${rd.text.slice(0, 160)}…`);
      if (toc.length >= 10 && rd.text.length > 300) passed.push({ name: src.name, url: src.url, exact: hits[pick].t === key, chapters: toc.length });
    }
  }
  if (passed.length) {
    say(`\n全部通过（搜索 → 目录 ≥ 10 章 → 正文）：${passed.map(p => `${p.name}（${p.chapters} 章${p.exact ? "" : "，书名不完全一致"}）`).join("、")}`);
    const keep = raw.filter(o => passed.some(p => p.name === o.bookSourceName && p.url === o.bookSourceUrl));
    fs.writeFileSync(path.join(OUT, "passed.json"), JSON.stringify(keep, null, 2));
    fs.writeFileSync(path.join(OUT, "passed-info.json"), JSON.stringify(passed, null, 2));
  }
  if (errors.length) say("\n页面脚本错误：" + [...new Set(errors)].slice(0, 8).join(" | "));
  finish(screen ? (passed.length ? 0 : 1) : (bad ? 1 : 0));

  async function finish(code){
    await browser.close();
    if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary.join("\n\n") + "\n");
    process.exit(code);
  }
})();
