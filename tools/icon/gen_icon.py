#!/usr/bin/env python3
"""Launcher icon generator.

Each design is one scene description emitted twice: as Android VectorDrawables (the adaptive-icon layers in
app/src/main/res/drawable) and as SVGs (for previews and the 512 px Play Store icon, see render.js).
Canvas is the 108 x 108 dp adaptive-icon grid; a launcher shows the central 72 dp, and the subject stays inside the
central 66 dp safe zone (circle of radius 33 around 54,54).

The monochrome (themed icon) layer needs polygon booleans and uses shapely:
    pip install shapely
    python3 tools/icon/gen_icon.py              # chosen design -> res/drawable/ic_launcher_*.xml and build/icon/*.svg
    python3 tools/icon/gen_icon.py --explore    # every design -> build/icon/<design>/*.svg (compare.js renders them)
"""
import os
import sys

from shapely.geometry import LineString, Point, Polygon
from shapely.ops import unary_union

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
SVG_OUT = os.path.join(ROOT, "build", "icon")
C = 54.0  # canvas centre


def fmt(n):
    s = f"{n:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def poly_d(ps):
    return "M" + " L".join(f"{fmt(x)},{fmt(y)}" for x, y in ps) + " Z"


def line_d(ps):
    return "M" + " L".join(f"{fmt(x)},{fmt(y)}" for x, y in ps)


def circle_d(cx, cy, r):
    return (f"M{fmt(cx - r)},{fmt(cy)} a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0 "
            f"a{fmt(r)},{fmt(r)} 0 1,0 {fmt(-2 * r)},0 Z")


def rrect_d(x, y, w, h, r):
    return (f"M{fmt(x + r)},{fmt(y)} L{fmt(x + w - r)},{fmt(y)} Q{fmt(x + w)},{fmt(y)} {fmt(x + w)},{fmt(y + r)} "
            f"L{fmt(x + w)},{fmt(y + h - r)} Q{fmt(x + w)},{fmt(y + h)} {fmt(x + w - r)},{fmt(y + h)} "
            f"L{fmt(x + r)},{fmt(y + h)} Q{fmt(x)},{fmt(y + h)} {fmt(x)},{fmt(y + h - r)} "
            f"L{fmt(x)},{fmt(y + r)} Q{fmt(x)},{fmt(y)} {fmt(x + r)},{fmt(y)} Z")


def rrect_poly(x, y, w, h, r):
    return unary_union([Polygon([(x + r, y), (x + w - r, y), (x + w - r, y + h), (x + r, y + h)]),
                        Polygon([(x, y + r), (x + w, y + r), (x + w, y + h - r), (x, y + h - r)])]
                       + [Point(px, py).buffer(r, quad_segs=6) for px in (x + r, x + w - r) for py in (y + r, y + h - r)])


# ---------------------------------------------------------------- element model
# ("fill", d, color, alpha) | ("stroke", d, color, width, alpha, cap, join) | ("grad", d, gradient)
# | ("group", (sx, sy, px, py), [elements]) ; gradient = (cx, cy, r, [(offset, color, alpha)])

def fill(d, color, alpha=1.0):
    return ("fill", d, color, alpha)


def stroke(d, color, width, alpha=1.0, cap="round", join="round"):
    return ("stroke", d, color, width, alpha, cap, join)


def grad(d, cx, cy, r, stops):
    return ("grad", d, (cx, cy, r, stops))


# ---------------------------------------------------------------- palette (the game's colours, a notch brighter)
FIBER, FIBER_D, FIBER_L = "#FF8A1C", "#C4560A", "#FFE3B8"
TEAL, TEAL_D = "#23B5A5", "#16847A"          # DSL
WINE, WINE_D = "#E0457B", "#A82C58"          # coax
BLUE, BLUE_D = "#1F7FC4", "#17639C"          # mail server roof
WHITE, WHITE_S = "#FFFFFF", "#D3DCE3"


def iso_box(cx, y0, hw, h):
    """Isometric box whose top face's back vertex is (cx, y0); hw = half width, h = height. Returns its faces."""
    top = [(cx, y0), (cx + hw, y0 + hw / 2), (cx, y0 + hw), (cx - hw, y0 + hw / 2)]
    left = [(cx - hw, y0 + hw / 2), (cx, y0 + hw), (cx, y0 + hw + h), (cx - hw, y0 + hw / 2 + h)]
    right = [(cx, y0 + hw), (cx + hw, y0 + hw / 2), (cx + hw, y0 + hw / 2 + h), (cx, y0 + hw + h)]
    outline = [(cx, y0), (cx + hw, y0 + hw / 2), (cx + hw, y0 + hw / 2 + h), (cx, y0 + hw + h),
               (cx - hw, y0 + hw / 2 + h), (cx - hw, y0 + hw / 2)]
    return {"top": top, "left": left, "right": right, "outline": outline}


def mono_layer(solid):
    clean = lambda g: g.buffer(0.05, quad_segs=2).buffer(-0.05, quad_segs=2).simplify(0.08)
    return [fill(d, "#000000", 1.0) for d in geom_paths(clean(solid))]


# ================================================================ design A: hub (flat, metro-map)
# A white server hub where three thick cable lines in the game's colours meet; each line carries a station ring.
HUB = 30.0
A_LINES = [  # points from the hub outwards, colour
    ([(C, C), (74, C), (96, 32)], FIBER),
    ([(C, C), (C, 76), (34, 96)], TEAL),
    ([(C, C), (34, C), (12, 32)], WINE),
]
A_STATIONS = [((78.5, 49.5), FIBER), ((C, 78.0), TEAL), ((29.5, 49.5), WINE)]
LINE_W = 10.0


def hub_fg():
    s = []
    for pts, col in A_LINES:
        s.append(stroke(line_d(pts), col, LINE_W, cap="butt", join="round"))
    for (x, y), col in A_STATIONS:
        s.append(fill(circle_d(x, y, 7.0), WHITE))
        s.append(fill(circle_d(x, y, 4.2), col))
        s.append(fill(circle_d(x, y, 2.2), WHITE))
    h = HUB / 2
    s.append(fill(rrect_d(C - h, C - h + 2.2, HUB, HUB, 7), "#081528", 0.45))   # drop shadow
    s.append(fill(rrect_d(C - h, C - h, HUB, HUB, 7), WHITE))
    s.append(fill(rrect_d(C - h + 5, C - 7.5, HUB - 10, 5.2, 2.6), BLUE))
    s.append(fill(rrect_d(C - h + 5, C + 2.3, HUB - 10, 5.2, 2.6), BLUE))
    for y in (C - 4.9, C + 4.9):
        s.append(fill(circle_d(C + 6.5, y, 1.4), "#FFFFFF"))
    return s


def hub_mono():
    lines = unary_union([LineString(p).buffer(LINE_W / 2, cap_style=2, join_style=1) for p, _ in A_LINES])
    st = unary_union([Point(x, y).buffer(7.0) for (x, y), _ in A_STATIONS])
    rings = unary_union([Point(x, y).buffer(4.2).difference(Point(x, y).buffer(2.2)) for (x, y), _ in A_STATIONS])
    h = HUB / 2
    hub = rrect_poly(C - h, C - h, HUB, HUB, 7)
    slots = unary_union([rrect_poly(C - h + 5, C - 7.5, HUB - 10, 5.2, 2.6),
                         rrect_poly(C - h + 5, C + 2.3, HUB - 10, 5.2, 2.6)])
    solid = unary_union([lines.difference(hub.buffer(2.0)).difference(st.buffer(1.4)), st.difference(rings),
                         hub.difference(slots)])
    return mono_layer(solid)


# ================================================================ design B: packet (one glowing cube on a fiber)
B_HW, B_H, B_Y0 = 20.0, 17.0, 31.0   # packet cube: half width, height, back vertex y
B_STREAKS = ((-9, 9), (0, 13), (9, 9))


def packet_fg():
    s = []
    fiber = [(6, 102), (102, 6)]
    s.append(stroke(line_d(fiber), FIBER_L, 26, 0.25, cap="butt"))
    s.append(stroke(line_d(fiber), FIBER_D, 17, cap="butt"))
    s.append(stroke(line_d(fiber), FIBER, 13, cap="butt"))
    s.append(stroke(line_d(fiber), FIBER_L, 3.4, cap="butt"))
    s.append(grad(circle_d(C, C, 34), C, C, 34, [(0, "#FFF6E0", 0.95), (0.45, "#FFD89A", 0.55), (1, "#FFD89A", 0)]))
    for off, ln in B_STREAKS:   # speed streaks behind the cube (towards the bottom left)
        x0, y0 = 30 + off * 0.7, 78 + off * 0.7
        s.append(stroke(line_d([(x0, y0), (x0 - ln * 0.7, y0 + ln * 0.7)]), "#FFFFFF", 3.2, 0.85))
    f = iso_box(C, B_Y0, B_HW, B_H)
    s.append(fill(poly_d(f["left"]), WHITE))
    s.append(fill(poly_d(f["right"]), "#F2D9B8"))
    s.append(fill(poly_d(f["top"]), "#FFB347"))
    cx, cy = C, B_Y0 + B_HW / 2
    s.append(fill(poly_d([(cx, cy - 5.5), (cx + 11, cy), (cx, cy + 5.5), (cx - 11, cy)]), WHITE))
    return s


def packet_mono():
    fiber = LineString([(6, 102), (102, 6)]).buffer(6.5, cap_style=2)
    f = iso_box(C, B_Y0, B_HW, B_H)
    cube = Polygon(f["outline"])
    cx, cy = C, B_Y0 + B_HW / 2
    mark = Polygon([(cx, cy - 5.5), (cx + 11, cy), (cx, cy + 5.5), (cx - 11, cy)])
    edges = LineString([f["left"][0], f["left"][1], f["right"][1]]).buffer(0.9, cap_style=2, join_style=2).union(
        LineString([f["left"][1], f["left"][2]]).buffer(0.9, cap_style=2))
    streaks = unary_union([LineString([(30 + o * 0.7, 78 + o * 0.7), (30 + o * 0.7 - n * 0.7, 78 + o * 0.7 + n * 0.7)])
                           .buffer(1.6) for o, n in B_STREAKS])
    solid = unary_union([fiber.difference(cube.buffer(2.2)).difference(streaks.buffer(1.4)), streaks,
                         cube.difference(unary_union([mark, edges]))])
    return mono_layer(solid)


# ================================================================ design C: tower (one big iso server, one fiber)
T_X, T_Y0, T_HW, T_H = 50.0, 19.0, 18.0, 31.0   # tower: centre x, top back vertex y, half width, height
ROOF_TOP = "#3D9DE3"
P_HW, P_H = 8.5, 7.5                            # packet cube


def tower_ports():
    bottom_y = T_Y0 + T_HW + T_H
    right = (T_X + T_HW / 2, bottom_y - T_HW / 4)
    left = (T_X - T_HW / 2, bottom_y - T_HW / 4)
    return left, right


def along(p, dx, t):
    return (p[0] + dx * t, p[1] + t / 2 * abs(dx))


def tower_vents():
    out = []
    for k in (0.43, 0.68):
        for side, col in ((-1, BLUE), (1, BLUE_D)):
            a, b = T_HW * 0.24, T_HW * 0.76
            y = T_Y0 + T_H * k
            x0, x1 = T_X + side * a, T_X + side * b
            y0, y1 = y + T_HW - a / 2, y + T_HW - b / 2
            out.append(([(x0, y0), (x1, y1), (x1, y1 + 4.6), (x0, y0 + 4.6)], col))
    return out


def tower_scene(teal):
    left, right = tower_ports()
    fiber = [right, along(right, 1, 60)]
    dsl = [left, along(left, -1, 60)] if teal else None
    pc = along(right, 1, 17)                       # packet centre on the fiber
    return fiber, dsl, pc


def packet_box(pc):
    return iso_box(pc[0], pc[1] - P_HW - P_H + 2.5, P_HW, P_H)


def tower_fg_for(teal):
    s = []
    f = iso_box(T_X, T_Y0, T_HW, T_H)
    gx, gy = T_X, T_Y0 + T_HW + T_H - 4
    s.append(("group", (1.0, 0.5, gx, gy), [grad(circle_d(gx, gy, 30), gx, gy, 30,
                                                  [(0, "#050F20", 0.6), (0.6, "#050F20", 0.3), (1, "#050F20", 0)])]))
    fiber, dsl, pc = tower_scene(teal)
    if dsl:
        s.append(stroke(line_d(dsl), TEAL_D, 12, cap="butt"))
        s.append(stroke(line_d(dsl), TEAL, 8.4, cap="butt"))
    s.append(stroke(line_d(fiber), FIBER_D, 15, cap="butt"))
    s.append(stroke(line_d(fiber), FIBER, 11, cap="butt"))
    s.append(stroke(line_d(fiber), FIBER_L, 3.0, cap="butt"))
    s.append(fill(poly_d(f["left"]), WHITE))
    s.append(fill(poly_d(f["right"]), WHITE_S))
    band = iso_box(T_X, T_Y0, T_HW, 6.5)
    s.append(fill(poly_d(band["left"]), BLUE))
    s.append(fill(poly_d(band["right"]), BLUE_D))
    s.append(fill(poly_d(band["top"]), ROOF_TOP))
    cx, cy = T_X, T_Y0 + T_HW / 2
    s.append(fill(poly_d([(cx, cy - 3.6), (cx + 7.2, cy), (cx, cy + 3.6), (cx - 7.2, cy)]), WHITE))
    for q, col in tower_vents():
        s.append(fill(poly_d(q), col))
    gx, gy = pc[0], pc[1] - 6
    s.append(grad(circle_d(gx, gy, 16), gx, gy, 16, [(0, "#FFF6E0", 1), (0.45, "#FFD89A", 0.65), (1, "#FFD89A", 0)]))
    k = packet_box(pc)
    s += [fill(poly_d(k["left"]), WHITE), fill(poly_d(k["right"]), "#F2D9B8"), fill(poly_d(k["top"]), "#FFB347")]
    return s


def tower_mono_for(teal):
    f = iso_box(T_X, T_Y0, T_HW, T_H)
    tower = Polygon(f["outline"])
    band = iso_box(T_X, T_Y0, T_HW, 6.5)
    line = LineString([band["left"][3], band["left"][2], band["right"][2]]).buffer(0.9, cap_style=2, join_style=2)
    cx, cy = T_X, T_Y0 + T_HW / 2
    mark = Polygon([(cx, cy - 3.6), (cx + 7.2, cy), (cx, cy + 3.6), (cx - 7.2, cy)])
    vents = [Polygon(q) for q, _ in tower_vents()]
    fiber, dsl, pc = tower_scene(teal)
    cables = LineString(fiber).buffer(7.5, cap_style=2)
    if dsl:
        cables = cables.union(LineString(dsl).buffer(6.0, cap_style=2))
    pk = Polygon(packet_box(pc)["outline"])
    solid = unary_union([tower.difference(unary_union([line, mark] + vents)),
                         cables.difference(tower.buffer(0.01)).difference(pk.buffer(1.8)), pk])
    return mono_layer(solid)


def tower_fg():
    return tower_fg_for(True)


def tower_mono():
    return tower_mono_for(True)


# ================================================================ design D: rack (the in-game server)
# The game's own server look: stacked rack units (light frame, dark panel, blue separator), blue roof with the white
# mail square. Variants: small with one fiber and a packet ("rack"), big with three cables ("rackhub").
FRAME_L, FRAME_R = "#E3E7EB", "#AEB6C0"
PANEL_L, PANEL_R = "#3A424C", "#1F2B3D"
SRV, SRV_D, SRV_TOP = "#2F9ED8", "#2385BA", "#3FB0E8"
LED = "#6FC7F7"
ANTENNA, ANTENNA_TIP = "#3A424C", "#E5383B"
PKT = "#0C8DC3"
DUSK_C, DUSK_M, DUSK_E = "#2F6A80", "#1B495D", "#0E2A38"

RACK_SMALL = dict(cx=45.0, yb=70.0, hw=18.0, fl=13.0, n=2, band=2.6, slots=True)
RACK_BIG = dict(cx=C, yb=82.0, hw=25.0, fl=15.5, n=2, band=3.4, slots=False)


def rpt(r, face, s, z):
    """Point on the rack's left (-1) or right (+1) face: s in 0..1 from the front edge outwards, z height above ground."""
    return (r["cx"] + face * s * r["hw"], r["yb"] - s * r["hw"] / 2 - z)


def rquad(r, face, s0, s1, z0, z1):
    return [rpt(r, face, s0, z0), rpt(r, face, s1, z0), rpt(r, face, s1, z1), rpt(r, face, s0, z1)]


def rack_top(r):
    H = r["n"] * r["fl"]
    return [rpt(r, -1, 0, H), rpt(r, -1, 1, H), (r["cx"], r["yb"] - H - r["hw"]), rpt(r, 1, 1, H)]


def rack_outline(r):
    H = r["n"] * r["fl"]
    return [rpt(r, -1, 1, 0), rpt(r, -1, 0, 0), rpt(r, 1, 1, 0), rpt(r, 1, 1, H), (r["cx"], r["yb"] - H - r["hw"]),
            rpt(r, -1, 1, H)]


def rack_symbol(r):
    H = r["n"] * r["fl"]
    k = r["hw"] * 0.29
    tx, ty = r["cx"], r["yb"] - H - r["hw"] / 2
    return tx - k, ty - k - 0.12 * r["hw"], 2 * k


def rack_panels(r):
    return [(f, rquad(r, f, 0.12, 0.9, i * r["fl"] + 1.8, (i + 1) * r["fl"] - r["band"] - 1.6)) for f in (-1, 1)
            for i in range(r["n"])]


def rack_leds(r):
    out = []
    for f in (-1, 1):
        for i in range(r["n"]):
            z0 = i * r["fl"]
            m = 2 if r["slots"] else 1
            for j in range(m):
                zz = z0 + 3.4 + j * 3.2 if r["slots"] else z0 + r["fl"] * 0.34
                hh = 1.8 if r["slots"] else r["fl"] * 0.16
                out.append((f, rquad(r, f, 0.2, 0.36, zz, zz + hh), rquad(r, f, 0.46, 0.8, zz, zz + min(hh, 1.6))))
    return out


def rack_body(r):
    s = []
    H = r["n"] * r["fl"]
    for i in range(r["n"]):
        z0 = i * r["fl"]
        for face, frame in ((-1, FRAME_L), (1, FRAME_R)):
            s.append(fill(poly_d(rquad(r, face, 0, 1, z0, z0 + r["fl"])), frame))
            s.append(fill(poly_d(rquad(r, face, 0, 1, z0 + r["fl"] - r["band"], z0 + r["fl"])), SRV if face < 0 else SRV_D))
    for f, q in rack_panels(r):
        s.append(fill(poly_d(q), PANEL_L if f < 0 else PANEL_R))
    for f, led, slot in rack_leds(r):
        s.append(fill(poly_d(led), LED))
        if r["slots"]:
            s.append(fill(poly_d(slot), "#0F1620", 0.8))
    s.append(fill(poly_d(rack_top(r)), SRV_TOP))
    x, y, w = rack_symbol(r)
    (ax, ay0), (_, ay1), ar = rack_antenna(r)
    s.append(stroke(line_d([(ax, ay0), (ax, ay1)]), ANTENNA, ar * 0.8, cap="butt"))
    s.append(fill(circle_d(ax, ay1, ar), ANTENNA_TIP))
    s.append(fill(rrect_d(x, y, w, w, w * 0.12), WHITE))
    return s


def rack_antenna(r):
    x, y, w = rack_symbol(r)
    ax = x + w / 2
    return (ax, y + w / 2), (ax, y - r["hw"] * 0.15), r["hw"] * 0.085


def rack_body_mono(r):
    H = r["n"] * r["fl"]
    rack = Polygon(rack_outline(r))
    cuts = [Polygon(q) for _, q in rack_panels(r)]
    edge = LineString([rpt(r, -1, 1, H), rpt(r, -1, 0, H), rpt(r, 1, 1, H)]).buffer(0.8, cap_style=2, join_style=2)
    x, y, w = rack_symbol(r)
    sym = rrect_poly(x, y, w, w, w * 0.12)
    (ax, ay0), (_, ay1), ar = rack_antenna(r)
    antenna = LineString([(ax, ay0), (ax, ay1)]).buffer(ar * 0.4, cap_style=2).union(Point(ax, ay1).buffer(ar))
    leds = unary_union([Polygon(led) for _, led, _ in rack_leds(r)])
    body = rack.difference(unary_union(cuts + [edge, sym.buffer(1.2)]))
    return rack, unary_union([body, leds, sym, antenna.difference(sym.buffer(1.2))])


def contact_shadow(r):
    gx, gy = r["cx"] + 2, r["yb"] - r["hw"] * 0.35
    sh = "#050F20"
    rad = r["hw"] * 1.65
    return ("group", (1.0, 0.5, gx, gy), [grad(circle_d(gx, gy, rad), gx, gy, rad,
                                                [(0, sh, 0.55), (0.6, sh, 0.25), (1, sh, 0)])])


def cable(pts, col, dark, w, core=None):
    out = [stroke(line_d(pts), dark, w, cap="butt"), stroke(line_d(pts), col, w - 3.2, cap="butt")]
    if core:
        out.append(stroke(line_d(pts), core, 2.8, cap="butt"))
    return out


# ---- small rack with one fiber and a packet
def rack_scene():
    r = RACK_SMALL
    port = rpt(r, 1, 0.5, 1.0)
    fiber = [port, (port[0] + 70, port[1] + 35)]
    pc = (port[0] + 15, port[1] + 6)
    return fiber, pc


def rack_fg():
    r = RACK_SMALL
    fiber, (px, py) = rack_scene()
    s = [contact_shadow(r)]
    s += cable(fiber, "#FF8800", "#D96A00", 14, "#FFE3B8")
    s += rack_body(r)
    s.append(grad(circle_d(px, py, 13), px, py, 13, [(0, "#FFFFFF", 0.7), (0.5, "#FFFFFF", 0.3), (1, "#FFFFFF", 0)]))
    s.append(fill(rrect_d(px - 8, py - 8, 16, 16, 2.4), WHITE))
    s.append(fill(rrect_d(px - 5.4, py - 5.4, 10.8, 10.8, 1), PKT))
    return s


def rack_mono():
    r = RACK_SMALL
    rack, body = rack_body_mono(r)
    fiber, (px, py) = rack_scene()
    cab = LineString(fiber).buffer(7.0, cap_style=2)
    pk = rrect_poly(px - 8, py - 8, 16, 16, 2.4)
    hole = Polygon([(px - 5.4, py - 5.4), (px + 5.4, py - 5.4), (px + 5.4, py + 5.4), (px - 5.4, py + 5.4)])
    return mono_layer(unary_union([body, cab.difference(rack.buffer(1.4)).difference(pk.buffer(1.6)), pk.difference(hole)]))


# ---- big rack with three cables in the game's colours running off the tile
def rackhub_cables():
    r = RACK_BIG
    right = rpt(r, 1, 0.5, 1.5)
    left = rpt(r, -1, 0.5, 1.5)
    back = rpt(r, 1, 1, r["fl"] * 0.6)   # leaves behind the right back corner, to the upper right
    return [
        ([back, (back[0] + 60, back[1] - 30)], WINE, WINE_D, 12.0),
        ([left, (left[0] - 60, left[1] + 30)], TEAL, TEAL_D, 12.0),
        ([right, (right[0] + 60, right[1] + 30)], "#FF8800", "#D96A00", 15.0),
    ]


def rackhub_fg():
    r = RACK_BIG
    s = [grad(circle_d(C, 50, 40), C, 50, 40, [(0, "#7CC6D8", 0.55), (0.55, "#4E97AE", 0.25), (1, "#4E97AE", 0)]),
         contact_shadow(r)]
    for pts, col, dark, w in rackhub_cables():
        s += cable(pts, col, dark, w, "#FFE3B8" if col == "#FF8800" else None)
    s += rack_body(r)
    return s


def rackhub_mono():
    r = RACK_BIG
    rack, body = rack_body_mono(r)
    cabs = unary_union([LineString(p).buffer(w / 2 - 0.5, cap_style=2) for p, _, _, w in rackhub_cables()])
    return mono_layer(unary_union([body, cabs.difference(rack.buffer(1.4))]))


def dusk_background():
    return [grad("M0,0 L108,0 L108,108 L0,108 Z", C, 48, 80, [(0, DUSK_C, 1), (0.5, DUSK_M, 1), (1, DUSK_E, 1)])]


DESIGNS = {   # the explored alternatives (compare.js); CHOSEN is the one the app ships
    "hub": (dusk_background, hub_fg, hub_mono),
    "packet": (dusk_background, packet_fg, packet_mono),
    "tower": (dusk_background, tower_fg, tower_mono),
    "rack": (dusk_background, rack_fg, rack_mono),
    "rackhub": (dusk_background, rackhub_fg, rackhub_mono),
}
CHOSEN = "rackhub"
COMMENTS = {
    "background": "Launcher background: the brand's dusk blue (store bar, menu header), lighter in the middle.",
    "foreground": "Launcher foreground: one big server in the game's own look (rack units, blue roof, mail square,\n"
                  "     antenna) with glass fiber, DSL and coax cables leaving it. 108 dp canvas, inside the 66 dp safe zone.",
    "monochrome": "Themed-icon layer (Android 13+): the server with panels cut out, and the three cable stubs.",
}


def geom_paths(g, limit=780):
    """Polygon(s) -> list of path strings (1 decimal, holes as evenOdd subpaths), each short enough for lint."""
    f1 = lambda n: (f"{n:.1f}".rstrip("0").rstrip(".") or "0")
    ring_d = lambda coords: "M" + " L".join(f"{f1(x)},{f1(y)}" for x, y in list(coords)[:-1]) + " Z"
    chunks, cur = [], ""
    for p in getattr(g, "geoms", [g]):
        if p.is_empty or p.area < 2.0:
            continue  # drop specks
        d = " ".join(ring_d(r.coords) for r in [p.exterior] + [i for i in p.interiors if Polygon(i).area >= 2.0])
        if cur and len(cur) + len(d) + 1 > limit:
            chunks.append(cur)
            cur = ""
        cur = f"{cur} {d}".strip()
    if cur:
        chunks.append(cur)
    return chunks


# ---------------------------------------------------------------- emitters
def hexa(color, alpha=1.0):
    return "#{:02X}{}".format(round(alpha * 255), color[1:].upper())


def to_svg(elements, name):
    defs, body = [], []

    def emit(el, ind=""):
        kind = el[0]
        if kind == "fill":
            _, d, c, a = el
            op = f' fill-opacity="{fmt(a)}"' if a < 1 else ""
            body.append(f'{ind}<path d="{d}" fill="{c}"{op} fill-rule="evenodd"/>')
        elif kind == "stroke":
            _, d, c, w, a, cap, join = el
            op = f' stroke-opacity="{fmt(a)}"' if a < 1 else ""
            body.append(f'{ind}<path d="{d}" fill="none" stroke="{c}" stroke-width="{fmt(w)}"{op} '
                        f'stroke-linecap="{cap}" stroke-linejoin="{join}"/>')
        elif kind == "grad":
            _, d, (cx, cy, r, stops) = el
            gid = f"g{len(defs)}"
            st = "".join(f'<stop offset="{fmt(o)}" stop-color="{c}" stop-opacity="{fmt(a)}"/>' for o, c, a in stops)
            defs.append(f'<radialGradient id="{gid}" cx="{fmt(cx)}" cy="{fmt(cy)}" r="{fmt(r)}" '
                        f'gradientUnits="userSpaceOnUse">{st}</radialGradient>')
            body.append(f'{ind}<path d="{d}" fill="url(#{gid})"/>')
        elif kind == "group":
            _, (sx, sy, px, py), children = el
            body.append(f'<g transform="translate({fmt(px)},{fmt(py)}) scale({fmt(sx)},{fmt(sy)}) '
                        f'translate({fmt(-px)},{fmt(-py)})">')
            for ch in children:
                emit(ch, "  ")
            body.append("</g>")

    for el in elements:
        emit(el)
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">'
           f'<defs>{"".join(defs)}</defs>\n' + "\n".join(body) + "\n</svg>\n")
    with open(os.path.join(SVG_OUT, name), "w") as f:
        f.write(svg)


def to_vector(elements, name, comment):
    uses_aapt = any(e[0] == "grad" or (e[0] == "group" and any(c[0] == "grad" for c in e[2])) for e in elements)
    out = ['<?xml version="1.0" encoding="utf-8"?>', f"<!-- {comment}", "     Generated by tools/icon/gen_icon.py; edit the script, not this file. -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"']
    if uses_aapt:
        out.append('    xmlns:aapt="http://schemas.android.com/aapt"')
    out += ['    android:width="108dp"', '    android:height="108dp"',
            '    android:viewportWidth="108"', '    android:viewportHeight="108">']

    def emit(el, ind):
        kind = el[0]
        if kind == "fill":
            _, d, c, a = el
            out.append(f"{ind}<path")
            out.append(f'{ind}    android:fillColor="{hexa(c, a)}"')
            if " M" in d:
                out.append(f'{ind}    android:fillType="evenOdd"')
            out.append(f'{ind}    android:pathData="{d}" />')
        elif kind == "stroke":
            _, d, c, w, a, cap, join = el
            out.append(f"{ind}<path")
            out.append(f'{ind}    android:strokeColor="{hexa(c, a)}"')
            out.append(f'{ind}    android:strokeWidth="{fmt(w)}"')
            out.append(f'{ind}    android:strokeLineCap="{cap}"')
            out.append(f'{ind}    android:strokeLineJoin="{join}"')
            out.append(f'{ind}    android:pathData="{d}" />')
        elif kind == "grad":
            _, d, (cx, cy, r, stops) = el
            out.append(f'{ind}<path android:pathData="{d}">')
            out.append(f'{ind}    <aapt:attr name="android:fillColor">')
            out.append(f'{ind}        <gradient android:type="radial" android:centerX="{fmt(cx)}" '
                       f'android:centerY="{fmt(cy)}" android:gradientRadius="{fmt(r)}">')
            for o, c, a in stops:
                out.append(f'{ind}            <item android:offset="{fmt(o)}" android:color="{hexa(c, a)}" />')
            out.append(f"{ind}        </gradient>")
            out.append(f"{ind}    </aapt:attr>")
            out.append(f"{ind}</path>")
        elif kind == "group":
            _, (sx, sy, px, py), children = el
            out.append(f'{ind}<group android:pivotX="{fmt(px)}" android:pivotY="{fmt(py)}" '
                       f'android:scaleX="{fmt(sx)}" android:scaleY="{fmt(sy)}">')
            for ch in children:
                emit(ch, ind + "    ")
            out.append(f"{ind}</group>")

    for el in elements:
        emit(el, "    ")
    out.append("</vector>")
    with open(os.path.join(RES, name), "w") as f:
        f.write("\n".join(out) + "\n")




def write_svgs(design, out_dir):
    global SVG_OUT
    SVG_OUT = out_dir
    os.makedirs(out_dir, exist_ok=True)
    bg, fg, mono = DESIGNS[design]
    for name, els in (("background", bg()), ("foreground", fg()), ("monochrome", mono())):
        to_svg(els, f"{name}.svg")


if __name__ == "__main__":
    if "--explore" in sys.argv:
        for key in DESIGNS:
            write_svgs(key, os.path.join(ROOT, "build", "icon", "explore", key))
        print("explore ok")
        sys.exit(0)
    write_svgs(CHOSEN, SVG_OUT)
    bg, fg, mono = DESIGNS[CHOSEN]
    for name, els in (("background", bg()), ("foreground", fg()), ("monochrome", mono())):
        to_vector(els, f"ic_launcher_{name}.xml", COMMENTS[name])
    print("ok")
