#!/usr/bin/env python3
"""
Draws the original 64x64 pixel-art badge icon for Waystones Item Cost Extended 2.0.0
and exports:
  - docs/artwork/icon-64.png (64x64 native pixel grid)
  - common/src/main/resources/icon.png (512x512, 8x nearest-neighbor upscale)
"""

from pathlib import Path
import math
from PIL import Image

W = H = 64

# Cohesive limited pixel-art palette (RGBA)
TRANSPARENT = (0, 0, 0, 0)

# Stone badge frame
FRAME_OUTLINE = (15, 18, 28, 255)
FRAME_DARK = (36, 42, 58, 255)
FRAME_MID = (58, 67, 88, 255)
FRAME_LIGHT = (92, 104, 130, 255)
FRAME_HIGHLIGHT = (130, 144, 174, 255)

# Interior dark sanctuary backdrop
BG_DEEP = (18, 22, 34, 255)
BG_MID = (26, 31, 48, 255)
BG_GLOW_PURPLE = (44, 34, 68, 255)
BG_GLOW_TEAL = (24, 52, 56, 255)

# Waystone obelisk stone
STONE_OUTLINE = (20, 23, 33, 255)
STONE_SHADOW = (64, 71, 87, 255)
STONE_BASE = (104, 113, 131, 255)
STONE_LIGHT = (146, 156, 174, 255)
STONE_HI = (188, 198, 214, 255)

# Amethyst arcane runes
RUNE_DARK = (114, 48, 198, 255)
RUNE_MID = (174, 96, 255, 255)
RUNE_BRIGHT = (228, 188, 255, 255)
RUNE_WHITE = (250, 240, 255, 255)

# Emerald palette (block, cut gem, crystal shards)
EM_OUTLINE = (10, 42, 26, 255)
EM_DARK = (14, 102, 56, 255)
EM_MID = (24, 168, 86, 255)
EM_BRIGHT = (74, 224, 126, 255)
EM_SPEC = (186, 255, 206, 255)

# Subtle golden/mint orbital arc
ARC_DIM = (42, 136, 98, 255)
ARC_BRIGHT = (112, 238, 168, 255)


def rounded_box_dist(x: float, y: float, cx: float, cy: float, half: float, r: float) -> float:
    dx = abs(x - cx) - (half - r)
    dy = abs(y - cy) - (half - r)
    ax = max(dx, 0.0)
    ay = max(dy, 0.0)
    return math.hypot(ax, ay) + min(max(dx, dy), 0.0) - r


def set_px(img: Image.Image, x: int, y: int, color: tuple[int, int, int, int]) -> None:
    if 0 <= x < W and 0 <= y < H:
        img.putpixel((x, y), color)


def fill_rect(img: Image.Image, x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int, int]) -> None:
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            set_px(img, x, y, color)


def draw_badge(img: Image.Image) -> None:
    cx = cy = 31.5
    for y in range(H):
        for x in range(W):
            d = rounded_box_dist(x, y, cx, cy, 30.0, 9.5)
            if d > 0.5:
                continue
            if d > -1.0:
                set_px(img, x, y, FRAME_OUTLINE)
            elif d > -2.5:
                # Top-left light bevel, bottom-right dark bevel
                if x + y < 58:
                    set_px(img, x, y, FRAME_LIGHT)
                elif x + y > 68:
                    set_px(img, x, y, FRAME_DARK)
                else:
                    set_px(img, x, y, FRAME_MID)
            elif d > -4.0:
                if (x in (5, 58) or y in (5, 58)) and ((x + y) % 7 == 0):
                    set_px(img, x, y, FRAME_DARK)
                else:
                    set_px(img, x, y, FRAME_MID)
            elif d > -5.0:
                set_px(img, x, y, FRAME_OUTLINE)
            else:
                # Interior background with soft amethyst top glow & emerald lower glow
                dist_rune = math.hypot(x - 31.5, y - 24.0)
                dist_arc = math.hypot(x - 31.5, y - 42.0)
                if dist_rune < 14.5:
                    set_px(img, x, y, BG_GLOW_PURPLE)
                elif dist_arc < 19.0 and y > 29:
                    set_px(img, x, y, BG_GLOW_TEAL)
                elif dist_rune < 22.0:
                    set_px(img, x, y, BG_MID)
                else:
                    set_px(img, x, y, BG_DEEP)

    # Corner stone rivets on the frame
    for rx, ry in [(8, 8), (55, 8), (8, 55), (55, 55)]:
        set_px(img, rx, ry, FRAME_HIGHLIGHT)
        set_px(img, rx + 1, ry + 1, FRAME_DARK)


def draw_orbital_arc(img: Image.Image) -> None:
    """Graceful luminous emerald arc sweeping behind the obelisk and linking the emerald forms."""
    # Back upper-left to upper-right halo arc
    for deg in range(200, 345, 3):
        rad = math.radians(deg)
        ex = 31.5 + 21.0 * math.cos(rad)
        ey = 30.0 + 16.5 * math.sin(rad)
        ix, iy = int(round(ex)), int(round(ey))
        if 8 <= ix <= 55 and 8 <= iy <= 55:
            set_px(img, ix, iy, ARC_DIM if (deg % 6 == 0) else BG_GLOW_TEAL)


def draw_foreground_arc(img: Image.Image) -> None:
    """Clean luminous energy ribbon linking the emerald block -> center shard -> cut emerald."""
    for deg in range(15, 166, 2):
        rad = math.radians(deg)
        ex = 31.5 + 19.5 * math.cos(rad)
        ey = 41.5 + 8.5 * math.sin(rad)
        ix, iy = int(round(ex)), int(round(ey))
        if 10 <= ix <= 53 and 36 <= iy <= 52:
            c = ARC_BRIGHT if (deg % 10 < 5) else ARC_DIM
            set_px(img, ix, iy, c)


def draw_waystone(img: Image.Image) -> None:
    """Centered carved Waystone obelisk with glowing amethyst runes."""
    # Stepped pedestal base
    # Lower plinth: x=20..43, y=49..53
    fill_rect(img, 19, 48, 44, 54, STONE_OUTLINE)
    fill_rect(img, 20, 49, 43, 53, STONE_BASE)
    fill_rect(img, 20, 49, 43, 49, STONE_LIGHT)
    fill_rect(img, 20, 50, 21, 53, STONE_LIGHT)
    fill_rect(img, 41, 50, 43, 53, STONE_SHADOW)
    fill_rect(img, 20, 53, 43, 53, STONE_SHADOW)

    # Upper plinth: x=23..40, y=44..48
    fill_rect(img, 22, 43, 41, 48, STONE_OUTLINE)
    fill_rect(img, 23, 44, 40, 48, STONE_BASE)
    fill_rect(img, 23, 44, 40, 44, STONE_HI)
    fill_rect(img, 23, 45, 24, 48, STONE_LIGHT)
    fill_rect(img, 38, 45, 40, 48, STONE_SHADOW)

    # Main obelisk shaft: x=25..38, y=15..43
    fill_rect(img, 24, 14, 39, 43, STONE_OUTLINE)
    fill_rect(img, 25, 15, 38, 43, STONE_BASE)
    # Left bevel highlight & right shadow
    fill_rect(img, 25, 15, 27, 43, STONE_LIGHT)
    fill_rect(img, 25, 15, 25, 42, STONE_HI)
    fill_rect(img, 36, 15, 38, 43, STONE_SHADOW)

    # Carved cap / crown: y=9..15
    fill_rect(img, 26, 11, 37, 14, STONE_OUTLINE)
    fill_rect(img, 27, 12, 36, 14, STONE_BASE)
    fill_rect(img, 27, 12, 29, 14, STONE_HI)
    fill_rect(img, 35, 12, 36, 14, STONE_SHADOW)
    # Pyramid tip: y=8..11
    fill_rect(img, 28, 9, 35, 11, STONE_OUTLINE)
    fill_rect(img, 29, 10, 34, 11, STONE_LIGHT)
    fill_rect(img, 30, 8, 33, 9, STONE_OUTLINE)
    fill_rect(img, 31, 9, 32, 9, STONE_HI)

    # Horizontal stone masonry grooves
    for gy in (21, 29, 37):
        fill_rect(img, 25, gy, 38, gy, STONE_SHADOW)
        set_px(img, 25, gy - 1, STONE_HI)

    # Central recessed rune channel (x=29..34, y=16..41)
    fill_rect(img, 29, 16, 34, 41, STONE_OUTLINE)
    fill_rect(img, 30, 17, 33, 40, RUNE_DARK)

    # Glowing amethyst rune glyphs inside the channel
    # Top diamond crest (y=17..22)
    fill_rect(img, 31, 17, 32, 22, RUNE_MID)
    fill_rect(img, 30, 19, 33, 20, RUNE_BRIGHT)
    set_px(img, 31, 19, RUNE_WHITE)
    set_px(img, 32, 20, RUNE_WHITE)

    # Mid arcane sigil (y=25..31)
    fill_rect(img, 30, 25, 33, 25, RUNE_MID)
    fill_rect(img, 31, 26, 32, 30, RUNE_BRIGHT)
    fill_rect(img, 30, 28, 33, 28, RUNE_BRIGHT)
    set_px(img, 31, 28, RUNE_WHITE)
    set_px(img, 32, 27, RUNE_WHITE)

    # Lower rune pillar (y=34..39)
    fill_rect(img, 31, 34, 32, 39, RUNE_MID)
    set_px(img, 30, 35, RUNE_MID)
    set_px(img, 33, 35, RUNE_MID)
    set_px(img, 31, 36, RUNE_BRIGHT)
    set_px(img, 32, 36, RUNE_WHITE)
    set_px(img, 31, 38, RUNE_BRIGHT)

    # Subtle glowing side notches
    for ny in (19, 28, 36):
        set_px(img, 28, ny, RUNE_DARK)
        set_px(img, 35, ny, RUNE_DARK)


def draw_emerald_block(img: Image.Image, x0: int, y0: int) -> None:
    """14x14 faceted Minecraft-style Emerald Block on the left flank."""
    size = 13
    # Outer dark outline
    fill_rect(img, x0, y0, x0 + size, y0 + size, EM_OUTLINE)
    # Main emerald body
    fill_rect(img, x0 + 1, y0 + 1, x0 + size - 1, y0 + size - 1, EM_MID)
    # Outer bevel frame
    fill_rect(img, x0 + 1, y0 + 1, x0 + size - 1, y0 + 2, EM_BRIGHT)
    fill_rect(img, x0 + 1, y0 + 1, x0 + 2, y0 + size - 1, EM_BRIGHT)
    fill_rect(img, x0 + 1, y0 + size - 2, x0 + size - 1, y0 + size - 1, EM_DARK)
    fill_rect(img, x0 + size - 2, y0 + 1, x0 + size - 1, y0 + size - 1, EM_DARK)
    # Inner bevelled diamond/square facet
    fill_rect(img, x0 + 3, y0 + 3, x0 + size - 3, y0 + size - 3, EM_DARK)
    fill_rect(img, x0 + 4, y0 + 4, x0 + size - 4, y0 + size - 4, EM_MID)
    fill_rect(img, x0 + 4, y0 + 4, x0 + size - 5, y0 + 5, EM_BRIGHT)
    fill_rect(img, x0 + 4, y0 + 4, x0 + 5, y0 + size - 5, EM_BRIGHT)
    # Crisp specular glints
    set_px(img, x0 + 2, y0 + 2, EM_SPEC)
    set_px(img, x0 + 3, y0 + 2, EM_SPEC)
    set_px(img, x0 + 2, y0 + 3, EM_SPEC)
    set_px(img, x0 + 5, y0 + 5, EM_SPEC)
    set_px(img, x0 + 6, y0 + 5, EM_SPEC)


def draw_cut_emerald(img: Image.Image, cx: int, cy: int) -> None:
    """Crisp octagonal cut Emerald gem (11x13) on the right flank."""
    # Shape rows: half-widths for dy = -6..6
    profile = {
        -6: 1,
        -5: 2,
        -4: 3,
        -3: 4,
        -2: 5,
        -1: 5,
        0: 5,
        1: 5,
        2: 5,
        3: 4,
        4: 3,
        5: 2,
        6: 1,
    }
    for dy, hw in profile.items():
        for dx in range(-hw, hw + 1):
            x, y = cx + dx, cy + dy
            if abs(dx) == hw or dy in (-6, 6):
                set_px(img, x, y, EM_OUTLINE)
            elif dx <= -hw + 1 or dy <= -4:
                set_px(img, x, y, EM_BRIGHT)
            elif dx >= hw - 1 or dy >= 4:
                set_px(img, x, y, EM_DARK)
            else:
                set_px(img, x, y, EM_MID)

    # Inner table facet
    fill_rect(img, cx - 2, cy - 3, cx + 2, cy + 3, EM_BRIGHT)
    fill_rect(img, cx - 1, cy - 2, cx + 1, cy + 2, EM_MID)
    fill_rect(img, cx + 1, cy + 1, cx + 2, cy + 3, EM_DARK)
    # Specular highlight
    set_px(img, cx - 2, cy - 3, EM_SPEC)
    set_px(img, cx - 1, cy - 3, EM_SPEC)
    set_px(img, cx - 2, cy - 2, EM_SPEC)
    set_px(img, cx - 1, cy - 1, EM_SPEC)


def draw_crystal_shard(img: Image.Image, cx: int, cy: int, scale: int = 1) -> None:
    """Small faceted emerald crystal grain along the arc."""
    if scale == 1:
        # 5x5 diamond shard with outline
        for dx, dy in [(0, -2), (-1, -1), (1, -1), (-2, 0), (2, 0), (-1, 1), (1, 1), (0, 2)]:
            set_px(img, cx + dx, cy + dy, EM_OUTLINE)
        set_px(img, cx, cy - 1, EM_SPEC)
        set_px(img, cx - 1, cy, EM_BRIGHT)
        set_px(img, cx, cy, EM_MID)
        set_px(img, cx + 1, cy, EM_DARK)
        set_px(img, cx, cy + 1, EM_DARK)
    else:
        # 3x3 micro sparkle grain
        for dx, dy in [(0, -1), (-1, 0), (1, 0), (0, 1)]:
            set_px(img, cx + dx, cy + dy, EM_BRIGHT)
        set_px(img, cx, cy, EM_SPEC)


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    img = Image.new("RGBA", (W, H), TRANSPARENT)

    draw_badge(img)
    draw_orbital_arc(img)
    draw_waystone(img)
    draw_foreground_arc(img)

    # Foreground emerald arc elements flanking the monument cleanly
    draw_emerald_block(img, 9, 36)
    draw_cut_emerald(img, 49, 38)
    # Crystal grains completing the upper-right and lower-center arc
    draw_crystal_shard(img, 18, 21, scale=1)
    draw_crystal_shard(img, 46, 20, scale=1)
    draw_crystal_shard(img, 32, 50, scale=1)
    draw_crystal_shard(img, 13, 28, scale=0)
    draw_crystal_shard(img, 51, 27, scale=0)

    out_64 = root / "docs" / "artwork" / "icon-64.png"
    out_64.parent.mkdir(parents=True, exist_ok=True)
    img.save(out_64, "PNG")

    out_512 = root / "common" / "src" / "main" / "resources" / "icon.png"
    out_512.parent.mkdir(parents=True, exist_ok=True)
    upscaled = img.resize((512, 512), Image.Resampling.NEAREST)
    upscaled.save(out_512, "PNG")
    print(f"Generated {out_64} (64x64) and {out_512} (512x512)")


if __name__ == "__main__":
    main()
