// Renders the LeXia brand pictures: node docs/brand/render.js
const path = require('path');
const { chromium } = require('playwright');
const res = path.join(__dirname, '../../app/src/main/res/drawable-nodpi');
const out = {
  icon: [path.join(res, 'ic_launcher.png'), path.join(__dirname, 'emblem.png')],
  banner: [path.join(res, 'banner.png')],
  social: [path.join(__dirname, 'social-preview.png')],
};
(async () => {
  require('fs').mkdirSync(res, { recursive: true });
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1400, height: 800 } });
  await p.goto('file://' + path.join(__dirname, 'brand.html'));
  await p.evaluate(() => document.fonts.ready);
  await p.waitForTimeout(200);
  for (const [id, files] of Object.entries(out)) {
    // The icon keeps see-through corners.
    await p.evaluate(t => { document.body.style.background = t ? 'transparent' : '#000'; }, id === 'icon');
    for (const f of files) await p.locator('#' + id).screenshot({ path: f, omitBackground: id === 'icon' });
    console.log(id, files.map(f => path.basename(f)).join(', '));
  }
  await b.close();
})();
