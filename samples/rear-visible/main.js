var canvas = document.getElementById("rear");
var ctx = canvas.getContext("2d");

// Widget screenWidth is 912. The camera island covers the left side,
// so this offline sample draws only in the right-hand visible band.
var screenW = 912;
var screenH = 412;
var visibleLeft = 268;
var pad = 20;
var x = visibleLeft + pad;
var y = pad;
var w = screenW - x - pad;
var h = screenH - pad * 2;

ctx.clearRect(0, 0, screenW, screenH);
ctx.fillStyle = "#14161c";
roundRect(ctx, x, y, w, h, 28);
ctx.fill();

ctx.fillStyle = "#8ab4ff";
ctx.font = "28px sans-serif";
ctx.fillText("背屏卡片", x + 28, y + 64);

ctx.fillStyle = "#f2f4f8";
ctx.font = "64px sans-serif";
ctx.fillText("12:30", x + 28, y + 150);

ctx.fillStyle = "#9aa3b2";
ctx.font = "24px sans-serif";
ctx.fillText("右侧可视区域", x + 28, y + 210);

function roundRect(c, rx, ry, rw, rh, r) {
  c.beginPath();
  c.moveTo(rx + r, ry);
  c.arcTo(rx + rw, ry, rx + rw, ry + rh, r);
  c.arcTo(rx + rw, ry + rh, rx, ry + rh, r);
  c.arcTo(rx, ry + rh, rx, ry, r);
  c.arcTo(rx, ry, rx + rw, ry, r);
  c.closePath();
}
