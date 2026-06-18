from PIL import Image, ImageDraw, ImageFilter
import math, os

def get_locus_bg(size):
    """Crop the puzzle-piece square from the Locus logo, scale to size."""
    raw = Image.open("locus_logo_raw.png").convert("RGBA")
    w, h = raw.size
    # The puzzle icon occupies roughly the leftmost square (height x height)
    icon = raw.crop((0, 0, h, h))
    icon = icon.resize((size, size), Image.LANCZOS)
    return icon

def draw_icon(size):
    # 1. Locus puzzle icon as background
    bg = get_locus_bg(size)

    # 2. Dark semi-transparent overlay so our elements stand out
    overlay = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d_ov = ImageDraw.Draw(overlay)
    r = int(size * 0.18)
    d_ov.rounded_rectangle([0, 0, size, size], radius=r, fill=(10, 30, 80, 160))
    bg = Image.alpha_composite(bg, overlay)

    d = ImageDraw.Draw(bg)
    s = size / 512
    cx, cy = size / 2, size * 0.52

    # 3. Bicycle wheel – spokes
    wr       = size * 0.30
    spoke_w  = max(1, int(5 * s))
    arc_w    = max(3, int(14 * s))
    for i in range(12):
        angle = math.radians(i * 30)
        x2 = cx + wr * math.cos(angle)
        y2 = cy + wr * math.sin(angle)
        d.line([(cx, cy), (x2, y2)], fill=(255, 255, 255, 200), width=spoke_w)

    # Wheel rim
    bb = [cx - wr, cy - wr, cx + wr, cy + wr]
    d.ellipse(bb, outline=(255, 255, 255, 220), width=arc_w)

    # Hub – dark circle as bolt background
    hub_r = int(52 * s)
    d.ellipse([cx - hub_r, cy - hub_r, cx + hub_r, cy + hub_r],
               fill=(10, 30, 80, 240), outline=(255, 255, 255, 230),
               width=max(2, int(5 * s)))

    # 4. Lightning bolt – large, yellow, clearly visible
    bx, by = cx, cy
    bs = s * 5.0
    bolt = [
        (bx + 10*bs, by - 20*bs),
        (bx -  3*bs, by -  1*bs),
        (bx +  6*bs, by -  1*bs),
        (bx - 10*bs, by + 20*bs),
        (bx +  3*bs, by +  1*bs),
        (bx -  6*bs, by +  1*bs),
    ]
    d.polygon(bolt, fill=(255, 215, 0))

    # 5. Rounded corner mask so the icon stays within Play Store shape
    mask = Image.new("L", (size, size), 0)
    dm = ImageDraw.Draw(mask)
    r2 = int(size * 0.18)
    dm.rounded_rectangle([0, 0, size, size], radius=r2, fill=255)
    bg.putalpha(mask)

    return bg


# Play Store icon – solid white background (no transparency allowed)
icon = draw_icon(512)
store = Image.new("RGB", (512, 512), (255, 255, 255))
store.paste(icon, mask=icon.split()[3])
store.save("icon_playstore.png")
print("Saved icon_playstore.png")

# Android mipmap sizes
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
base = os.path.join("app", "src", "main", "res")
for density, px in sizes.items():
    folder = os.path.join(base, f"mipmap-{density}")
    os.makedirs(folder, exist_ok=True)
    ic = draw_icon(px)
    path = os.path.join(folder, "ic_launcher.png")
    ic.save(path)
    print(f"Saved {path} ({px}x{px})")

print("Done.")
