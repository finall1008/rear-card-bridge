#!/usr/bin/env python3
"""Create two tiny Canvas packages for the live-update UI."""
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

root = Path(__file__).resolve().parent
html = '<!doctype html><html><body><canvas id="rear"></canvas><script src="main.js"></script></body></html>'
for label, color in [('A', '#163858'), ('B', '#125932')]:
    script = '''var canvas = document.getElementById('rear');
var ctx = canvas.getContext('2d');
var width = window.CANVAS_W || 912, height = window.CANVAS_H || 596;
var started = Date.now(), taps = 0;
canvas.addEventListener('click', function () { taps++; });
function draw() {
  ctx.fillStyle = 'COLOR'; ctx.fillRect(0, 0, width, height);
  ctx.fillStyle = '#ffffff'; ctx.font = 'bold 28px sans-serif';
  ctx.fillText('动态更新 · LABEL', width * 0.34, 70);
  ctx.font = 'bold 110px sans-serif'; ctx.fillText('LABEL', width * 0.46, height * 0.52);
  ctx.font = '22px sans-serif';
  ctx.fillText('运行 ' + Math.floor((Date.now() - started) / 1000) + ' 秒 · 点击 ' + taps, width * 0.34, height * 0.76);
  requestAnimationFrame(draw);
}
console.log('RearCardBridgeDemo LABEL started');
requestAnimationFrame(draw);
'''.replace('COLOR', color).replace('LABEL', label)
    output = root / f'live-demo-{label.lower()}.zip'
    with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
        archive.writestr('index.html', html)
        archive.writestr('main.js', script)
    print(output)
