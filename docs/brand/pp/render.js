// Renders docs/brand/pp/pp.html into 1024 x 1024 PNGs: node docs/brand/pp/render.js
const path = require('path');
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1024, height: 1024 } });
  await p.goto('file://' + path.join(__dirname, 'pp.html'));
  await p.evaluate(() => document.fonts.ready);
  for (const id of ['amblem', 'yazili', 'admin']) {
    await p.locator('#' + id).screenshot({ path: path.join(__dirname, 'lexia-' + id + '.png') });
    console.log(id);
  }
  await b.close();
})();
