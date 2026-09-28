// Rebuild the app icon from the original bridge-link illustration.
// Run from any directory with: node tools/icon/build_icon.js
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const root = path.join(__dirname, '..', '..');
const source = path.join(__dirname, 'bridge-link-source.png');
const drawable = path.join(root, 'app/src/main/res/drawable-nodpi');
const store = path.join(root, 'docs/store');
const screenshot = path.join(root, 'docs/screenshots');

async function buildIcon() {
  fs.mkdirSync(drawable, { recursive: true });
  const original = await sharp(source).png().toBuffer();
  const info = await sharp(original).metadata();
  if (info.width !== 512 || info.height !== 512) {
    throw new Error('Expected a 512 × 512 px source icon');
  }

  // Android shows the central 72 dp of each 108 dp adaptive layer. Keep the
  // original image pixel-perfect in that region and extend its edge colours
  // for launcher parallax outside it.
  const padded = await sharp(original)
    .extend({ top: 128, bottom: 128, left: 128, right: 128, extendWith: 'copy' })
    .png()
    .toBuffer();
  fs.writeFileSync(path.join(drawable, 'ic_launcher_art.png'), padded);

  const rgba = await sharp(original).ensureAlpha().png().toBuffer();
  fs.writeFileSync(path.join(store, 'icon-512.png'), rgba);
  fs.writeFileSync(path.join(screenshot, 'store-icon-512.png'), rgba);

  const makeMask = (radius) => Buffer.from(`<svg width="256" height="256"><rect width="256" height="256" rx="${radius}" fill="white"/></svg>`);
  const thumb = await sharp(original).resize(256, 256).png().toBuffer();
  const circle = await sharp(thumb).composite([{ input: makeMask(128), blend: 'dest-in' }]).png().toBuffer();
  const round = await sharp(thumb).composite([{ input: makeMask(57), blend: 'dest-in' }]).png().toBuffer();
  const small = await sharp(original).resize(72, 72).png().toBuffer();
  const preview = await sharp({ create: { width: 690, height: 300, channels: 4, background: '#EEF2F4' } })
    .composite([
      { input: circle, left: 20, top: 20 },
      { input: round, left: 296, top: 20 },
      { input: small, left: 580, top: 30 },
    ])
    .png()
    .toBuffer();
  fs.writeFileSync(path.join(store, 'icon-preview.png'), preview);
}

module.exports = buildIcon;
if (require.main === module) buildIcon().catch(error => { console.error(error); process.exitCode = 1; });
