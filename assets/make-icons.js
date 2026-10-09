// 生成自适应图标各密度资源：源图裁中心方形 → 缩放到 mipmap-*
const fs = require('fs');
const path = require('path');
const { PNG } = require('/data/user/0/com.deepseek.harness/files/work/node_modules/pngjs');

const ROOT = '/sdcard/DeepSeekHarness/_scratch/wxstealth';
const src = PNG.sync.read(fs.readFileSync(path.join(ROOT, 'assets/logo-src.png')));

// 中心正方形
const side = Math.min(src.width, src.height);
const ox = Math.floor((src.width - side) / 2);
const oy = Math.floor((src.height - side) / 2);

// 采样：把源图中心方形映射到目标方形的内框（pad 是四周留白比例）
function render(size, pad) {
  const out = new PNG({ width: size, height: size });
  const inner = Math.max(1, Math.round(size * (1 - 2 * pad)));
  const off = Math.round((size - inner) / 2);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 255, g = 255, b = 255, a = 0;
      if (x >= off && x < off + inner && y >= off && y < off + inner) {
        const sx = ox + Math.min(side - 1, Math.floor((x - off) * side / inner));
        const sy = oy + Math.min(side - 1, Math.floor((y - off) * side / inner));
        const si = (sy * src.width + sx) * 4;
        r = src.data[si]; g = src.data[si + 1]; b = src.data[si + 2]; a = src.data[si + 3];
      }
      const di = (y * size + x) * 4;
      out.data[di] = r; out.data[di + 1] = g; out.data[di + 2] = b; out.data[di + 3] = a;
    }
  }
  return out;
}

// 传统图标（各密度，48dp 基准）
const legacy = { 'mipmap-mdpi': 48, 'mipmap-hdpi': 72, 'mipmap-xhdpi': 96, 'mipmap-xxhdpi': 144, 'mipmap-xxxhdpi': 192 };
// 自适应图标前景/背景（108dp 基准；前景留 18% 安全边距，避免被裁成圆/方时切到鲸鱼）
const adaptive = { 'mipmap-mdpi': 108, 'mipmap-hdpi': 162, 'mipmap-xhdpi': 216, 'mipmap-xxhdpi': 324, 'mipmap-xxxhdpi': 432 };

let n = 0;
for (const [dir, size] of Object.entries(legacy)) {
  const d = path.join(ROOT, 'res', dir);
  fs.mkdirSync(d, { recursive: true });
  fs.writeFileSync(path.join(d, 'ic_launcher.png'), PNG.sync.write(render(size, 0.04)));
  n++;
}
for (const [dir, size] of Object.entries(adaptive)) {
  const d = path.join(ROOT, 'res', dir);
  fs.mkdirSync(d, { recursive: true });
  fs.writeFileSync(path.join(d, 'ic_launcher_foreground.png'), PNG.sync.write(render(size, 0.18)));
  // 背景层：纯白，与源图底色一致
  const bg = new PNG({ width: size, height: size });
  for (let i = 0; i < size * size; i++) {
    bg.data[i * 4] = 255; bg.data[i * 4 + 1] = 255; bg.data[i * 4 + 2] = 255; bg.data[i * 4 + 3] = 255;
  }
  fs.writeFileSync(path.join(d, 'ic_launcher_background.png'), PNG.sync.write(bg));
  n++;
}
console.log('生成图标文件:', n);
