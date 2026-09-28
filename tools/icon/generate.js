const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const root = process.argv[2] || path.join(__dirname, '..', '..');
const out = path.join(root, 'docs/icon-explorations');
fs.mkdirSync(out, { recursive: true });
const C = { navy:'#163B53', navy2:'#103047', cream:'#F9F7ED', cyan:'#69C9DA', blue:'#2E86AB', orange:'#FF9F43', orangeDark:'#C86927', mint:'#ADDCAA', pale:'#D9EFF0' };
const variants=[];
const pathEl=(d,fill='none',stroke=null,width=0)=>({d,fill,stroke,width});
const circ=(x,y,r)=>`M${x-r},${y} a${r},${r} 0 1,0 ${2*r},0 a${r},${r} 0 1,0 ${-2*r},0 Z`;
const rr=(x,y,w,h,r)=>`M${x+r},${y} H${x+w-r} Q${x+w},${y} ${x+w},${y+r} V${y+h-r} Q${x+w},${y+h} ${x+w-r},${y+h} H${x+r} Q${x},${y+h} ${x},${y+h-r} V${y+r} Q${x},${y} ${x+r},${y} Z`;
const diamond=(x,y,r)=>`M${x},${y-r} L${x+r},${y} L${x},${y+r} L${x-r},${y} Z`;
const base=(name,title,description,bg=C.navy)=>({name,title,description,bg,shapes:[]});
const add=(v,...shapes)=>{v.shapes.push(...shapes);variants.push(v)};

let v=base('01-fiber-link','Fiber Link','Zwei Geräte, ein leuchtendes Kabel');
add(v,
 pathEl('M34,35 C40,35 43,40 47,47 C51,54 56,61 74,69','none',C.orangeDark,10),
 pathEl('M34,35 C40,35 43,40 47,47 C51,54 56,61 74,69','none',C.orange,6),
 pathEl(rr(23,24,21,21,4),C.cream),pathEl(rr(27,28,13,9,1.5),C.cyan),
 pathEl(rr(64,63,21,21,4),C.cream),pathEl(rr(68,67,13,9,1.5),C.mint),
 pathEl(diamond(53,54,5),C.cream));

v=base('02-network-hub','Network Hub','Drei Dienste an einem zentralen Router');
add(v,
 pathEl('M54,54 L32,32 M54,54 L77,32 M54,54 L54,78','none',C.orangeDark,10),
 pathEl('M54,54 L32,32 M54,54 L77,32 M54,54 L54,78','none',C.orange,6),
 pathEl(circ(54,54,13),C.cream),pathEl(circ(54,54,6),C.blue),
 pathEl(rr(24,24,16,16,3),C.cyan),pathEl(rr(69,24,16,16,3),C.mint),
 pathEl(rr(46,70,16,16,3),C.cream));

v=base('03-bridge','River Bridge','Eine Verbindung über den Fluss');
add(v,
 pathEl('M18,36 L90,70 L90,83 L18,49 Z',C.blue),
 pathEl('M18,45 L90,79','none',C.cyan,2),
 pathEl('M31,72 Q54,31 77,36','none',C.orangeDark,12),
 pathEl('M31,72 Q54,31 77,36','none',C.orange,8),
 pathEl(rr(22,64,18,18,3),C.cream),pathEl(rr(68,27,18,18,3),C.cream),
 pathEl(diamond(55,45,5),C.cream));

v=base('04-network-n','Network N','Markantes N aus verbundenen Knoten');
add(v,
 pathEl('M31,76 L31,31 L77,76 L77,31','none',C.orangeDark,13),
 pathEl('M31,76 L31,31 L77,76 L77,31','none',C.orange,9),
 pathEl(rr(23,23,16,16,4),C.cream),pathEl(rr(69,23,16,16,4),C.cyan),
 pathEl(rr(23,68,16,16,4),C.mint),pathEl(rr(69,68,16,16,4),C.cream));

v=base('05-packet-route','Packet Route','Ein Paket folgt einer L-förmigen Leitung');
add(v,
 pathEl('M37,40 H63 Q70,40 70,47 V70','none',C.orangeDark,12),
 pathEl('M37,40 H63 Q70,40 70,47 V70','none',C.orange,8),
 pathEl(rr(29,32,16,16,3),C.cream),pathEl(rr(33,36,8,8,1),C.cyan),
 pathEl(rr(61,61,18,18,3),C.cream),pathEl(rr(65,65,10,10,1),C.mint),
 pathEl(diamond(57,40,5),C.cream));

v=base('06-isometric-island','Network Island','Isometrische Spielwelt mit Verbindung', '#7BBACC');
add(v,
 pathEl('M54,20 L88,38 L54,56 L20,38 Z','#E5F0D9'),
 pathEl('M20,38 L54,56 L54,84 L20,66 Z','#9FBF96'),
 pathEl('M54,56 L88,38 L88,66 L54,84 Z','#80AB80'),
 pathEl('M34,45 L66,61','none',C.orangeDark,9),
 pathEl('M34,45 L66,61','none',C.orange,6),
 pathEl('M32,26 L46,33 L46,51 L32,44 Z',C.cream),
 pathEl('M46,33 L53,29 L53,47 L46,51 Z','#CBD9D7'),
 pathEl('M32,26 L39,22 L53,29 L46,33 Z',C.blue),
 pathEl('M60,48 L71,54 L71,69 L60,63 Z',C.cream),
 pathEl('M71,54 L78,50 L78,65 L71,69 Z','#CBD9D7'),
 pathEl('M60,48 L67,44 L78,50 L71,54 Z',C.mint),
 pathEl(diamond(53,54,4),C.cream));

v=base('07-signal','Signal','Router mit zwei Funkzielen');
add(v,
 pathEl('M54,66 L31,77 M54,66 L77,77','none',C.orangeDark,11),
 pathEl('M54,66 L31,77 M54,66 L77,77','none',C.orange,7),
 pathEl(rr(43,56,22,22,5),C.cream),pathEl(circ(54,67,4),C.blue),
 pathEl(circ(31,77,9),C.cyan),pathEl(circ(77,77,9),C.mint),
 pathEl('M41,43 Q54,30 67,43 M34,35 Q54,15 74,35','none',C.cream,5));

function svg(v){
 const s=v.shapes.map(x=>`<path d="${x.d}" fill="${x.fill}"${x.stroke?` stroke="${x.stroke}" stroke-width="${x.width}" stroke-linecap="round" stroke-linejoin="round"`:''}/>`).join('');
 return `<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108"><path fill="${v.bg}" d="M0 0H108V108H0Z"/>${s}</svg>`;
}
function xml(v,mode){
 const shapes=mode==='mono'?[v.shapes[0],v.shapes[2],v.shapes[4]]:v.shapes;
 const lines=shapes.map(x=>{
   const attrs=[`android:pathData="${x.d}"`,`android:fillColor="${x.fill==='none'?'#00000000':mode==='mono'?'#FF000000':x.fill}"`];
   if(x.stroke){attrs.push(`android:strokeColor="${mode==='mono'?'#FF000000':x.stroke}"`,`android:strokeWidth="${x.width}"`,`android:strokeLineCap="round"`,`android:strokeLineJoin="round"`)}
   return `    <path ${attrs.join(' ')} />`;
 }).join('\n');
 return `<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n${lines}\n</vector>\n`;
}

(async()=>{
 const browser=await chromium.launch();const page=await browser.newPage({viewport:{width:512,height:512},deviceScaleFactor:1});
 for(const item of variants){
  fs.writeFileSync(path.join(out,item.name+'.svg'),svg(item));
  await page.setContent(`<body style="margin:0"><img src="data:image/svg+xml;base64,${Buffer.from(svg(item)).toString('base64')}" style="position:absolute;width:768px;height:768px;left:-128px;top:-128px"></body>`);
  await page.locator('img').evaluate(img=>img.decode());
  await page.screenshot({path:path.join(out,item.name+'.png')});
 }
 const cells=variants.map((item,i)=>`<div style="width:248px;padding:16px;background:#fff;border-radius:16px;box-shadow:0 4px 18px #0002"><img src="data:image/png;base64,${fs.readFileSync(path.join(out,item.name+'.png')).toString('base64')}" style="width:216px;height:216px;border-radius:48px"><div style="display:flex;gap:12px;align-items:center;margin-top:12px"><img src="data:image/png;base64,${fs.readFileSync(path.join(out,item.name+'.png')).toString('base64')}" style="width:48px;height:48px;border-radius:12px"><div><b>${i+1}. ${item.title}</b><br><span style="font-size:12px">${item.description}</span></div></div></div>`).join('');
 await page.setViewportSize({width:900,height:950});
 await page.setContent(`<body style="margin:0;padding:24px;background:#E9EEF0;font:15px Arial;color:#263640"><h1 style="margin:0 0 18px">Mini Networks · 7 Icon-Varianten</h1><div style="display:flex;flex-wrap:wrap;gap:16px">${cells}</div></body>`);
 await page.screenshot({path:path.join(out,'overview.png'),fullPage:true});
 await browser.close();
 await require('./build_icon.js')();
})();
