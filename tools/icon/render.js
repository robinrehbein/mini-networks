// Renders the launcher icon SVGs from gen_icon.py into the Play Store icon and a preview sheet.
//   python3 tools/icon/gen_icon.py && NODE_PATH=/opt/node22/lib/node_modules node tools/icon/render.js
// Writes docs/store/icon-512.png (full-bleed square, Play applies its own mask) and docs/store/icon-preview.png.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..', '..');
const src = path.join(root, 'build', 'icon');
const out = path.join(root, 'docs', 'store');
fs.mkdirSync(out, { recursive: true });
const u = f => 'data:image/svg+xml;base64,' + fs.readFileSync(path.join(src, f)).toString('base64');
const BG = u('background.svg'), FG = u('foreground.svg'), MO = u('monochrome.svg');

// A launcher shows the central 72 of the 108 dp layers; S is the visible size in px.
function icon(S, mask, mode) {
  const L = S * 108 / 72, o = -S * 18 / 72;
  const clip = mask === 'circle' ? 'border-radius:50%' : mask === 'round' ? 'border-radius:22%'
    : mask === 'square' ? '' : 'border-radius:36%';
  let layers;
  if (mode === 'mono-light' || mode === 'mono-dark') {
    const [bg, fg] = mode === 'mono-light' ? ['#DCE6F2', '#2D4A6B'] : ['#2A3442', '#C9DCF2'];
    layers = `<div style="position:absolute;inset:0;background:${bg}"></div><div style="position:absolute;left:${o}px;top:${o}px;width:${L}px;height:${L}px;background:${fg};-webkit-mask:url(${MO}) center/100% 100%"></div>`;
  } else {
    layers = `<img src="${BG}" style="position:absolute;left:${o}px;top:${o}px;width:${L}px;height:${L}px"><img src="${FG}" style="position:absolute;left:${o}px;top:${o}px;width:${L}px;height:${L}px">`;
  }
  return `<div style="position:relative;width:${S}px;height:${S}px;overflow:hidden;${clip};flex:none">${layers}</div>`;
}
const label = t => `<div style="font:600 13px sans-serif;color:inherit;padding:16px 24px 0">${t}</div>`;
const page = (body, w, h, bg) => `<html><body style="margin:0;width:${w}px;height:${h}px;background:${bg};font:12px sans-serif;color:#33404a">${body}</body></html>`;

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  const shot = async (html, w, h, f, opts = {}) => {
    await p.setViewportSize({ width: w, height: h });
    await p.setContent(html);
    await p.waitForTimeout(200);
    await p.screenshot({ path: f, ...opts });
  };
  // Play asks for a 32-bit PNG; Chromium drops the alpha channel of an opaque shot, so convert afterwards:
  //   python3 -c "from PIL import Image; f='docs/store/icon-512.png'; Image.open(f).convert('RGBA').save(f)"
  await shot(page(icon(512, 'square', 'color'), 512, 512, 'transparent'), 512, 512, path.join(out, 'icon-512.png'), { omitBackground: true });

  const W = 1000;
  const full = `<div style="position:relative;width:216px;height:216px;flex:none"><img src="${BG}" style="position:absolute;width:216px;height:216px"><img src="${FG}" style="position:absolute;width:216px;height:216px"><div style="position:absolute;left:36px;top:36px;width:144px;height:144px;border:1.5px dashed rgba(255,255,255,.8);border-radius:50%;box-sizing:border-box"></div></div>`;
  const masks = mode => ['circle', 'squircle', 'round'].map(m => icon(168, m, mode)).join('');
  const small = mode => [48, 72].map(s => ['circle', 'squircle', 'round'].map(m => icon(s, m, mode)).join('')).join('');
  const wall = (bg, fgc, mono) => `<div style="background:${bg};color:${fgc};padding:0 0 20px">${label(mono !== 'color' ? 'Themed icon' : 'Home screen, 48 and 72 dp')}<div style="display:flex;gap:22px;align-items:center;padding:14px 24px 0">${small(mono)}</div></div>`;
  const body = `
    ${label('Adaptive layers (108 dp, dashed = 66 dp safe zone) and launcher masks')}
    <div style="display:flex;gap:28px;padding:14px 24px 20px;align-items:center">${full}${masks('color')}</div>
    <div style="display:flex">
      <div style="flex:1">${wall('linear-gradient(135deg,#F4EEE4,#E3ECF2)', '#33404a', 'color')}${wall('linear-gradient(135deg,#F4EEE4,#E3ECF2)', '#33404a', 'mono-light')}</div>
      <div style="flex:1">${wall('linear-gradient(135deg,#1B1F27,#2A2F3A)', '#e6ebf0', 'color')}${wall('linear-gradient(135deg,#1B1F27,#2A2F3A)', '#e6ebf0', 'mono-dark')}</div>
    </div>`;
  await p.setViewportSize({ width: W, height: 200 });
  await p.setContent(page(body, W, 'auto', '#EEF0F2'));
  await p.waitForTimeout(200);
  await p.screenshot({ path: path.join(out, 'icon-preview.png'), fullPage: true });
  await b.close();
})();
