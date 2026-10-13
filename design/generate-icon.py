"""Generate the approved Xiaomi rear-display launcher artwork. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SIZE = 1536
OUTPUT = ROOT / "app/src/main/res/drawable-nodpi"
OUTPUT.mkdir(parents=True, exist_ok=True)

# Adaptive icon layers are square; Android supplies the launcher mask.
background = Image.new("RGB", (SIZE, SIZE))
pixels = background.load()
for y in range(SIZE):
    for x in range(SIZE):
        t = (x + y) / (2 * SIZE)
        pixels[x, y] = (int(26 - 15 * t), int(53 - 28 * t), int(91 - 40 * t))

art = Image.new("RGBA", (SIZE, SIZE))
shadow = Image.new("RGBA", (SIZE, SIZE))
draw = ImageDraw.Draw(shadow)
draw.rounded_rectangle((411, 312, 1179, 1233), radius=117, fill=(0, 0, 0, 100))
art = Image.alpha_composite(art, shadow.filter(ImageFilter.GaussianBlur(39)))
draw = ImageDraw.Draw(art)
draw.rounded_rectangle((384, 264, 1152, 1212), radius=111, fill=(222, 239, 250, 255))
draw.rounded_rectangle((411, 294, 1125, 726), radius=87, fill=(10, 22, 36, 255))
for y in (399, 594):
    draw.ellipse((453, y - 75, 603, y + 75), fill=(88, 121, 148, 255))
    draw.ellipse((465, y - 63, 591, y + 63), fill=(24, 43, 65, 255))
    draw.ellipse((489, y - 39, 567, y + 39), fill=(42, 81, 115, 255))
    draw.ellipse((498, y - 30, 528, y), fill=(112, 170, 199, 255))
draw.ellipse((630, 375, 660, 405), fill=(197, 217, 228, 255))
draw.rounded_rectangle((696, 333, 1074, 687), radius=54, fill=(72, 221, 201, 255))
draw.rounded_rectangle((750, 381, 936, 402), radius=10, fill=(223, 255, 248, 255))
draw.rounded_rectangle((750, 621, 843, 639), radius=9, fill=(223, 255, 248, 255))
shadow = Image.new("RGBA", (SIZE, SIZE))
draw = ImageDraw.Draw(shadow)
draw.rounded_rectangle((270, 813, 684, 1131), radius=54, fill=(0, 0, 0, 80))
art = Image.alpha_composite(art, shadow.filter(ImageFilter.GaussianBlur(24)))
draw = ImageDraw.Draw(art)
draw.rounded_rectangle((246, 789, 660, 1095), radius=54, fill=(75, 143, 250, 255))
draw.rounded_rectangle((291, 834, 465, 861), radius=13, fill=(184, 216, 255, 255))
draw.rounded_rectangle((291, 1023, 390, 1041), radius=9, fill=(184, 216, 255, 255))
points = [(453, 942), (768, 942), (882, 828), (882, 510)]
draw.line(points, fill=(247, 253, 255, 255), width=54, joint="curve")
for x, y in points:
    draw.ellipse((x - 27, y - 27, x + 27, y + 27), fill=(247, 253, 255, 255))
draw.line([(798, 594), (882, 510), (966, 594)], fill=(247, 253, 255, 255), width=54, joint="curve")
for x, y in [(798, 594), (966, 594)]:
    draw.ellipse((x - 27, y - 27, x + 27, y + 27), fill=(247, 253, 255, 255))

# Center the artwork and keep it inside the adaptive icon's circular safe zone.
centered = Image.new("RGBA", (SIZE, SIZE))
centered.alpha_composite(art, (69, 30))
safe_size = round(SIZE * 0.70)
foreground = Image.new("RGBA", (SIZE, SIZE))
margin = (SIZE - safe_size) // 2
foreground.alpha_composite(centered.resize((safe_size, safe_size), Image.Resampling.LANCZOS), (margin, margin))
background.resize((512, 512), Image.Resampling.LANCZOS).save(OUTPUT / "ic_launcher_background.png")
foreground.resize((512, 512), Image.Resampling.LANCZOS).save(OUTPUT / "ic_launcher_foreground.png")
