// Side-by-side comparison of the icon designs from `gen_icon.py --explore` (plus build/icon/explore/old if present)
// at 512, 72 and 48 px, next to three flat style references in the manner of top strategy/puzzle icons.
//   python3 tools/icon/gen_icon.py --explore && NODE_PATH=/opt/node22/lib/node_modules node tools/icon/compare.js
// Writes build/icon/compare-512.png and build/icon/compare-small.png (device scale 1: 48 px really is 48 px).
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..', '..');
const dir = path.join(root, 'build', 'icon', 'explore');
const b64 = s => 'data:image/svg+xml;base64,' + Buffer.from(s).toString('base64');
const file = (d, f) => 'data:image/svg+xml;base64,' + fs.readFileSync(path.join(dir, d, f)).toString('base64');
const designs = fs.readdirSync(dir).filter(d => fs.existsSync(path.join(dir, d, 'foreground.svg'))).sort();

// Style references: generic flat icons (not real products), bold shapes, one focal object, strong contrast.
const refs = [
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 72 72"><rect width="72" height="72" fill="#F4F1EA"/>
   <path d="M-2,46 L26,46 L44,28 L74,28" stroke="#E5383B" stroke-width="7" fill="none"/>
   <path d="M20,-2 L20,30 L50,60 L50,74" stroke="#1D6FD8" stroke-width="7" fill="none"/>
   <circle cx="20" cy="46" r="6" fill="#fff" stroke="#222" stroke-width="3"/><circle cx="44" cy="28" r="6" fill="#fff" stroke="#222" stroke-width="3"/></svg>`,
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 72 72"><rect width="72" height="72" fill="#6C3FD1"/>
   <rect x="14" y="14" width="20" height="20" rx="5" fill="#FFD23F"/><rect x="38" y="14" width="20" height="20" rx="5" fill="#fff"/>
   <rect x="38" y="38" width="20" height="20" rx="5" fill="#3DDC97"/></svg>`,
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 72 72"><rect width="72" height="72" fill="#C8312F"/>
   <path d="M20,22 h8 v6 h6 v-6 h4 v6 h6 v-6 h8 v36 h-32 Z" fill="#fff"/><rect x="31" y="44" width="10" height="14" rx="5" fill="#C8312F"/></svg>`,
].map(b64);

function icon(d, S, mask) {
  const L = S * 108 / 72, o = -S * 18 / 72;
  const clip = mask === 'circle' ? 'border-radius:50%' : mask === 'square' ? '' : 'border-radius:23%';
  return `<div style="position:relative;width:${S}px;height:${S}px;overflow:hidden;${clip};flex:none">
    <img src="${file(d, 'background.svg')}" style="position:absolute;left:${o}px;top:${o}px;width:${L}px;height:${L}px">
    <img src="${file(d, 'foreground.svg')}" style="position:absolute;left:${o}px;top:${o}px;width:${L}px;height:${L}px"></div>`;
}
function mono(d, S, dark) {
  const L = S * 108 / 72, o = -S * 18 / 72;
  const [bg, fg] = dark ? ['#2A3442', '#C9DCF2'] : ['#DCE6F2', '#2D4A6B'];
  return `<div style="position:relative;width:${S}px;height:${S}px;overflow:hidden;border-radius:50%;background:${bg};flex:none">
    <div style="position:absolute;left:${o}px;top:${o}px;width:${L}px;height:${L}px;background:${fg};-webkit-mask:url(${file(d, 'monochrome.svg')}) center/100% 100%"></div></div>`;
}
const ref = (u, S, r) => `<img src="${u}" style="width:${S}px;height:${S}px;border-radius:${r};flex:none">`;
const lab = t => `<div style="font:600 13px sans-serif;width:70px;flex:none">${t}</div>`;

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ deviceScaleFactor: 1 });
  const shoot = async (html, w, f) => {
    await p.setViewportSize({ width: w, height: 200 });
    await p.setContent(`<html><body style="margin:0;background:#E9ECEF;color:#223">${html}</body></html>`);
    await p.waitForTimeout(300);
    await p.screenshot({ path: path.join(root, 'build', 'icon', f), fullPage: true });
  };
  const big = designs.map(d => `<div style="padding:16px"><div style="font:700 20px sans-serif;margin-bottom:8px">${d}</div>${icon(d, 512, 'squircle')}</div>`).join('');
  await shoot(`<div style="display:flex;flex-wrap:wrap">${big}</div>`, 2 * 545, 'compare-512.png');

  const row = (bg, fg, dark) => designs.map(d => `<div style="display:flex;gap:18px;align-items:center;padding:10px 18px;background:${bg};color:${fg}">
      ${lab(d)}${icon(d, 72, 'squircle')}${icon(d, 72, 'circle')}${icon(d, 48, 'squircle')}${icon(d, 48, 'circle')}
      ${mono(d, 48, dark)}
      <div style="width:24px"></div>${refs.map(u => ref(u, 48, '23%')).join('')}${icon(d, 48, 'squircle')}${refs.map(u => ref(u, 48, '23%')).join('')}
    </div>`).join('');
  const html = `${row('linear-gradient(135deg,#F4EEE4,#DDE8F0)', '#223', false)}${row('linear-gradient(135deg,#171B22,#2A2F3A)', '#eee', true)}
    <div style="display:flex;gap:18px;padding:10px 18px;background:#5B7A99">${designs.map(d => icon(d, 48, 'circle')).join('')}${refs.map(u => ref(u, 48, '50%')).join('')}</div>`;
  await shoot(html, 1000, 'compare-small.png');
  await b.close();
})();
