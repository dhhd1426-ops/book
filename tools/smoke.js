// Smoke test for the single-file prototype. Run before every push:
//   node tools/smoke.js [path/to/index.html]
// Needs Playwright (require('playwright')). Exits 1 on any failure and writes
// screenshots of every state to tools/out/ for a quick look.
//
// Checks, on a phone and a tablet, in light and dark:
//  1. leak   – an element inside a hidden layer still shows (a child that sets
//              `visibility:visible` under a hidden parent: the bug where the
//              書庫 carousel appeared on top of 笔记)
//  2. errors – any uncaught script error
//  3. width  – the page scrolls sideways
//  4. pages  – each tab shows only its own page
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const file = path.resolve(process.argv[2] || 'app/src/main/assets/index.html');
const out = path.join(__dirname, 'out');
fs.mkdirSync(out, { recursive: true });

const LEAK = () => {
  const bad = [];
  for (const el of document.querySelectorAll('body *')) {
    const cs = getComputedStyle(el);
    if (cs.visibility !== 'visible' || cs.display === 'none') continue;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2 || r.bottom < 0 || r.top > innerHeight) continue;
    let p = el.parentElement, hiddenBy = null;
    while (p && p !== document.body) {
      const ps = getComputedStyle(p);
      if (ps.visibility === 'hidden' || ps.display === 'none') { hiddenBy = p; break; }
      p = p.parentElement;
    }
    if (hiddenBy) bad.push(`${el.tagName.toLowerCase()}.${[...el.classList].join('.')} shows inside hidden ${hiddenBy.tagName.toLowerCase()}#${hiddenBy.id}.${[...hiddenBy.classList].join('.')}`);
  }
  return [...new Set(bad)].slice(0, 8);
};
const WIDE = () => document.documentElement.scrollWidth > innerWidth + 1 ? [`page is ${document.documentElement.scrollWidth}px wide in a ${innerWidth}px window`] : [];
const PAGES = () => {
  const on = [...document.querySelectorAll('.view')].filter(v => getComputedStyle(v).display !== 'none').map(v => v.dataset.view);
  return on.length === 1 ? [] : [`${on.length} pages showing at once: ${on.join(', ')}`];
};

(async () => {
  const browser = await chromium.launch();
  const failures = [];
  for (const [w, h, device] of [[400, 860, 'phone'], [1180, 820, 'tablet']]) {
    for (const scheme of ['light', 'dark']) {
      const page = await browser.newPage({ viewport: { width: w, height: h }, colorScheme: scheme });
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      await page.goto('file://' + file);
      await page.waitForTimeout(1500);
      const tag = `${device}-${scheme}`;
      const check = async (state, extra = []) => {
        await page.screenshot({ path: path.join(out, `${tag}-${state}.png`) });
        const problems = [...await page.evaluate(LEAK), ...await page.evaluate(WIDE), ...extra];
        problems.forEach(p => failures.push(`[${tag}] ${state}: ${p}`));
      };
      const tab = async v => { await page.click(`[data-go="${v}"]`); await page.waitForTimeout(1600); await check(`tab-${v}`, await page.evaluate(PAGES)); };
      const esc = async () => { await page.keyboard.press('Escape'); await page.waitForTimeout(1400); };

      for (const v of ['shelf', 'notes', 'library', 'me']) await tab(v);
      // 发现 → book detail, 我的书
      await tab('library');
      await page.click('#hero .slide.on'); await page.waitForTimeout(2600); await check('discover-detail');
      await esc();
      await page.click('[data-seg="mine"]'); await page.waitForTimeout(800); await check('library-mine');
      // 书源管理
      await tab('me');
      await page.click('#openSources'); await page.waitForTimeout(1200); await check('sources');
      await page.click('#srcAdd'); await page.waitForTimeout(900); await check('sources-add');
      await esc(); await esc();
      // shelf: pull a book out, read it, open contents
      await tab('shelf');
      await page.click('#go'); await page.waitForTimeout(3800); await check('book-open');
      await page.click('.ddet .btn.primary, .dA .btn.primary, .sheet .btn.primary'); await page.waitForTimeout(1500); await check('reader');
      await page.click('[data-rd="toc"]', { force: true }); await page.waitForTimeout(1200); await check('contents');
      await esc(); await esc(); await esc(); await page.waitForTimeout(2500);
      await check('back-on-shelf', await page.evaluate(PAGES));

      errors.forEach(e => failures.push(`[${tag}] script error: ${e}`));
      await page.close();
    }
  }
  await browser.close();
  if (failures.length) { console.log(failures.join('\n')); console.log(`\n${failures.length} problem(s). Screenshots: ${out}`); process.exit(1); }
  console.log(`smoke test passed · screenshots in ${out}`);
})();
