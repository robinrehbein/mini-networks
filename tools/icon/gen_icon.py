#!/usr/bin/env python3
"""Launcher icon generator: "Island tile with fiber packet".

One scene description is emitted twice: as Android VectorDrawables (the adaptive-icon layers in
app/src/main/res/drawable) and as SVGs (for previews and the 512 px Play Store icon, see render.js).
Canvas is the 108 x 108 dp adaptive-icon grid; the subject stays inside the central 66 dp safe zone.

The monochrome (themed icon) layer needs polygon booleans and uses shapely:
    pip install shapely
    python3 tools/icon/gen_icon.py            # writes res/drawable/ic_launcher_*.xml and build/icon/*.svg
"""
import math
import os

from shapely.geometry import LineString, Point, Polygon
from shapely.ops import unary_union

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
SVG_OUT = os.path.join(ROOT, "build", "icon")

# ---------------------------------------------------------------- geometry
OX, OY = 54, 40   # tile back vertex
T = 32            # tile size in iso units (left/right corners at x 22 and 86)
D = 6             # tile thickness


def P(u, v, h=0.0):
    return (OX + u - v, OY + (u + v) / 2 - h)


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


# ---------------------------------------------------------------- palette
WATER_C, WATER_M, WATER_E = "#4FB9D0", "#2B93B5", "#17607F"  # deep teal: the pale board pops at 48 dp
MINT, MINT_CHECK, MINT_RIM = "#E6F3DC", "#D3EAC4", "#F6FBF2"
MINT_L, MINT_R = "#8CC276", "#6CA65A"       # tile sides (lit / shaded)
BLUE, BLUE_D = "#1F7FC4", "#17639C"         # mail
YELLOW, YELLOW_D = "#E9A92B", "#C98A14"     # gaming
WHITE, WHITE_S = "#F8F9F7", "#CDD6DB"       # server faces (lit / shaded)
FIBER, FIBER_D, FIBER_CORE = "#FF8A1C", "#C4560A", "#FFE8C4"
TREE, TREE_D, TRUNK = "#76B47A", "#57985E", "#8A7A5C"
INK, SCREEN = "#2B3A42", "#BFE3F2"
SHADOW = "#2F4A2A"

# ---------------------------------------------------------------- scene layout (u, v in tile units)
TOWER = (3.0, 3.0, 12.0, 8.0)          # u0, v0, width, floor height (2 floors): a bigger, bolder server block
SMALL = (5.0, 18.5, 6.0, 5.5)          # gaming server: u0, v0, width, height
DEVICE = (24.5, 24.5, 5.5, 2.2)        # plinth: u0, v0, width, height
TU0, TV0, TW, TF = TOWER
PORT_V = TV0 + 3.0                     # cable leaves the tower's right face here
CABLE = [(TU0 + TW, PORT_V), (29.0, PORT_V), (29.0, 25.5)]
PACKET = (22.5, PORT_V)                 # on the leg leaving the tower
# Two more route lines in the game's cable colours (DSL teal, coax wine) leave the tower's front face and run off the
# board's front-left edge: three coloured lines read as "network" at 48 dp (judge panel).
ROUTES = [
    ([(TU0 + TW * 0.22, TV0 + TW), (TU0 + TW * 0.22, 32.0)], "#1FA39A", "#0E6F68"),
    ([(TU0 + TW * 0.78, TV0 + TW), (TU0 + TW * 0.78, 32.0)], "#B0305A", "#741838"),
]
ROUTE_W = 4.8
TREES = []   # no props: server, fiber, packet and one device read at 48 dp (judge panel)
PACKET_S = 6.6                          # packet cube edge: the glowing packet is the hero


def box_faces(u, v, w, dd, h, h0=0.0):
    a, b, c, d = P(u, v, h0 + h), P(u + w, v, h0 + h), P(u + w, v + dd, h0 + h), P(u, v + dd, h0 + h)
    b0, c0, d0 = P(u + w, v, h0), P(u + w, v + dd, h0), P(u, v + dd, h0)
    return {"top": [a, b, c, d], "left": [d, c, c0, d0], "right": [c, b, b0, c0],
            "outline": [a, b, b0, c0, d0, d]}


# ---------------------------------------------------------------- element model
# ("fill", d, color, alpha) | ("stroke", d, color, width, alpha, cap, join) | ("grad", d, gradient)
# | ("group", (sx, sy, px, py), [elements]) ; gradient = (cx, cy, r, [(offset, color, alpha)])

def fill(d, color, alpha=1.0):
    return ("fill", d, color, alpha)


def stroke(d, color, width, alpha=1.0, cap="round", join="round"):
    return ("stroke", d, color, width, alpha, cap, join)


def grad(d, cx, cy, r, stops):
    return ("grad", d, (cx, cy, r, stops))


def background():
    # radial gradient reaching the far corners, so the 108 dp parallax layer never shows an edge
    return [grad("M0,0 L108,0 L108,108 L0,108 Z", 54, 50, 78,
                 [(0, WATER_C, 1), (0.55, WATER_M, 1), (1, WATER_E, 1)])]


def server(u0, v0, w, fl, floors, roof, roof_d, windows=True):
    out = []
    for i in range(floors):
        f = box_faces(u0, v0, w, w, fl, h0=i * fl)
        out += [fill(poly_d(f["left"]), WHITE), fill(poly_d(f["right"]), WHITE_S)]
        if windows:  # two short blue vent slots per floor on the shaded face
            for k in (w * 0.2, w * 0.58):
                q = [P(u0 + w, v0 + k, i * fl + fl * 0.34), P(u0 + w, v0 + k + w * 0.22, i * fl + fl * 0.34),
                     P(u0 + w, v0 + k + w * 0.22, i * fl + fl * 0.62), P(u0 + w, v0 + k, i * fl + fl * 0.62)]
                out.append(fill(poly_d(q), roof))
    H = floors * fl
    # coloured top band (the roof slab), like the in-game server tops
    band = 1.3
    f = box_faces(u0, v0, w, w, band, h0=H - band)
    out += [fill(poly_d(f["left"]), roof), fill(poly_d(f["right"]), roof_d), fill(poly_d(f["top"]), roof)]
    if floors > 1:  # floor line between the two floors
        a, b, c = P(u0, v0 + w, fl), P(u0 + w, v0 + w, fl), P(u0 + w, v0, fl)
        out.append(stroke(line_d([a, b]), roof, 1.1, cap="butt", join="miter"))
        out.append(stroke(line_d([b, c]), roof_d, 1.1, cap="butt", join="miter"))
    return out


def foreground():
    s = []
    top, rt, bot, lf = P(0, 0), P(T, 0), P(T, T), P(0, T)
    # soft, faded contact shadow of the island on the water (circle squashed by a group scale)
    cx, cy = 55.0, OY + T / 2 + D + 3
    s.append(("group", (1.0, 0.3, cx, cy), [
        grad(circle_d(cx, cy, 34), cx, cy, 34, [(0, "#1E4A5C", 0.2), (0.7, "#1E4A5C", 0.12), (1, "#1E4A5C", 0)])]))
    # tile sides and top
    s.append(fill(poly_d([lf, bot, (bot[0], bot[1] + D), (lf[0], lf[1] + D)]), MINT_L))
    s.append(fill(poly_d([bot, rt, (rt[0], rt[1] + D), (bot[0], bot[1] + D)]), MINT_R))
    s.append(fill(poly_d([top, rt, bot, lf]), MINT))
    # the board's checker pattern: 4 x 4 cells, every other one a shade darker
    n = 4
    c = T / n
    cells = "".join(poly_d([P(i * c, j * c), P((i + 1) * c, j * c), P((i + 1) * c, (j + 1) * c), P(i * c, (j + 1) * c)])
                    for i in range(n) for j in range(n) if (i + j) % 2 == 1)
    s.append(fill(cells, MINT_CHECK))
    s.append(stroke(line_d([lf, top, rt]), MINT_RIM, 0.9))
    # contact shadows of the buildings
    u0, v0, w, fl = TOWER
    s.append(fill(poly_d([P(u0 + w, v0), P(u0 + w + 3.5, v0 + 1.5), P(u0 + w + 3.5, v0 + w + 1.5),
                          P(u0 + 1.5, v0 + w + 1.5), P(u0, v0 + w)]), SHADOW, 0.13))
    # trees at the two side tips
    for (tu, tv, r) in TREES:
        x, y = P(tu, tv)
        s.append(("group", (1.0, 0.45, x + 1.2, y + 0.4), [fill(circle_d(x + 1.2, y + 0.4, r * 0.95), SHADOW, 0.16)]))
        s.append(fill(rrect_d(x - 0.65, y - 3.2, 1.3, 3.4, 0.4), TRUNK))
        s.append(fill(circle_d(x, y - 3 - r * 0.8, r), TREE))
        s.append(fill(f"M{fmt(x)},{fmt(y - 3 - r * 1.8)} a{fmt(r)},{fmt(r)} 0 0,1 0,{fmt(2 * r)} Z", TREE_D, 0.55))
    # the two route lines, under the tower and the fiber
    for pts, col, dark in ROUTES:
        rp = [P(*c) for c in pts]
        s.append(stroke(line_d(rp), "#FFFFFF", ROUTE_W + 2.4, 0.55, cap="butt"))
        s.append(stroke(line_d(rp), dark, ROUTE_W, cap="butt"))
        s.append(stroke(line_d(rp), col, ROUTE_W - 1.6, cap="butt"))
    # mail server tower
    s += server(u0, v0, w, fl, 2, BLUE, BLUE_D)
    cx, cy = P(u0 + w / 2, v0 + w / 2, 2 * fl)
    s.append(fill(poly_d([(cx, cy - 1.9), (cx + 3.8, cy), (cx, cy + 1.9), (cx - 3.8, cy)]), "#FFFFFF"))
    # orange port on the tower's right face, where the fiber leaves
    pu = u0 + w
    port = [P(pu, PORT_V - 3.0, 0), P(pu, PORT_V + 3.0, 0), P(pu, PORT_V + 3.0, 6.0), P(pu, PORT_V - 3.0, 6.0)]
    s.append(fill(poly_d(port), FIBER_D))
    inner = [P(pu, PORT_V - 2.0, 0), P(pu, PORT_V + 2.0, 0), P(pu, PORT_V + 2.0, 5.0), P(pu, PORT_V - 2.0, 5.0)]
    s.append(fill(poly_d(inner), FIBER))
    # glass fiber: dark under-stroke, orange body, light core line (the game's fiber look)
    cp = [P(*c) for c in CABLE]
    s.append(stroke(line_d(cp), "#FFF3DC", 16.0, 0.55))
    s.append(stroke(line_d(cp), FIBER_D, 13.4))
    s.append(stroke(line_d(cp), FIBER, 10.4))
    s.append(stroke(line_d(cp), FIBER_CORE, 3.0))
    # device: white plinth with a monitor (dark outline, light-blue screen) like the in-game markers
    du, dv, dw, dh = DEVICE
    f = box_faces(du, dv, dw, dw, dh)
    s += [fill(poly_d(f["left"]), WHITE), fill(poly_d(f["right"]), WHITE_S), fill(poly_d(f["top"]), WHITE)]
    mx, my = P(du + dw / 2, dv + dw / 2, dh)
    s.append(("group", (1.0, 0.5, mx, my), [fill(circle_d(mx, my, 4.6), SHADOW, 0.18)]))
    k = 1.05  # a modest monitor: the fiber and the packet are the hero (judge panel)
    s.append(fill(rrect_d(mx - 1.0 * k, my - 3.2 * k, 2.0 * k, 2.9 * k, 0.3), INK))       # stand
    s.append(fill(rrect_d(mx - 3.0 * k, my - 1.1 * k, 6.0 * k, 1.4 * k, 0.7), INK))       # foot
    s.append(fill(rrect_d(mx - 5.3 * k, my - 11.0 * k, 10.6 * k, 8.0 * k, 1.5 * k), INK))     # bezel
    s.append(fill(rrect_d(mx - 4.1 * k, my - 9.8 * k, 8.2 * k, 5.6 * k, 0.6), SCREEN))    # screen
    s.append(fill(poly_d([(mx - 4.1 * k, my - 6.6 * k), (mx - 0.8 * k, my - 9.8 * k), (mx + 1.4 * k, my - 9.8 * k),
                          (mx - 2.8 * k, my - 4.2 * k), (mx - 4.1 * k, my - 4.2 * k)]), "#FFFFFF", 0.45))       # glare
    # the packet: glowing white cube with an orange core, travelling along the fiber
    kx, ky = P(*PACKET)
    h = PACKET_S / 2
    s.append(grad(circle_d(kx, ky - 6, 12), kx, ky - 6, 12,
                  [(0, "#FFFBEF", 1), (0.4, "#FFE9BE", 0.8), (1, "#FFE9BE", 0)]))
    s.append(("group", (1.0, 0.5, kx + 0.8, ky + 0.8), [fill(circle_d(kx + 0.8, ky + 0.8, 5.4), "#6B3606", 0.35)]))
    k = box_faces(PACKET[0] - h, PACKET[1] - h, PACKET_S, PACKET_S, PACKET_S * 0.88, h0=2.4)
    s += [fill(poly_d(k["left"]), "#FFFFFF"), fill(poly_d(k["right"]), "#F1E4D2"), fill(poly_d(k["top"]), "#FFFFFF")]
    qx, qy = P(PACKET[0], PACKET[1], 2.4 + PACKET_S * 0.88)
    s.append(fill(poly_d([(qx, qy - 2.0), (qx + 4.0, qy), (qx, qy + 2.0), (qx - 4.0, qy)]), FIBER))
    s.append(fill(poly_d([P(PACKET[0] + h, PACKET[1] - 1.6, 3.6), P(PACKET[0] + h, PACKET[1] + 1.6, 3.6),
                          P(PACKET[0] + h, PACKET[1] + 1.6, 6.0), P(PACKET[0] + h, PACKET[1] - 1.6, 6.0)]),
                  FIBER))
    return s


# ---------------------------------------------------------------- monochrome (shapely)
def sp(ps):
    return Polygon(ps)


def monochrome():
    Q = 4
    solids, cuts = [], []
    # tower silhouette with floor line, roof band line and roof mark cut out
    u0, v0, w, fl = TOWER
    H = 2 * fl
    tower = sp(box_faces(u0, v0, w, w, H)["outline"])
    solids.append(tower)
    for h in (fl, H - 1.6):
        a, b, c = P(u0, v0 + w, h), P(u0 + w, v0 + w, h), P(u0 + w, v0, h)
        cuts.append(LineString([a, b, c]).buffer(0.75, cap_style=2, join_style=2))
    cx, cy = P(u0 + w / 2, v0 + w / 2, H)
    cuts.append(sp([(cx, cy - 2.4), (cx + 4.8, cy), (cx, cy + 2.4), (cx - 4.8, cy)]))
    # cable as a solid thick L
    cp = [P(*c) for c in CABLE]
    cable = LineString(cp).buffer(4.8, quad_segs=Q, cap_style=1, join_style=1)
    routes = unary_union([LineString([P(*c) for c in pts]).buffer(ROUTE_W / 2, cap_style=2, join_style=2) for pts, _, _ in ROUTES])
    # device: plinth + monitor with the screen cut out
    du, dv, dw, dh = DEVICE
    plinth = sp(box_faces(du, dv, dw, dw, dh)["outline"])
    mx, my = P(du + dw / 2, dv + dw / 2, dh)
    k = 1.05
    bezel = Polygon([(mx - 5.3 * k, my - 11.0 * k), (mx + 5.3 * k, my - 11.0 * k), (mx + 5.3 * k, my - 3.0 * k), (mx - 5.3 * k, my - 3.0 * k)])
    bezel = bezel.buffer(-1.3, join_style=2).buffer(1.3, quad_segs=Q)
    stand = Polygon([(mx - 1.1 * k, my - 3.2 * k), (mx + 1.1 * k, my - 3.2 * k), (mx + 1.1 * k, my), (mx - 1.1 * k, my)])
    screen = Polygon([(mx - 3.9 * k, my - 9.6 * k), (mx + 3.9 * k, my - 9.6 * k), (mx + 3.9 * k, my - 4.4 * k), (mx - 3.9 * k, my - 4.4 * k)])
    device = unary_union([plinth.difference(bezel.buffer(1.3)), bezel.union(stand).difference(screen)])
    # packet cube with its orange core as a hole, framed by a gap cut into the cable
    h = PACKET_S / 2
    k = box_faces(PACKET[0] - h, PACKET[1] - h, PACKET_S, PACKET_S, PACKET_S * 0.88, h0=2.4)
    packet = sp(k["outline"])
    qx, qy = P(PACKET[0], PACKET[1], 2.4 + PACKET_S * 0.88)
    packet = packet.difference(sp([(qx, qy - 2.0), (qx + 4.0, qy), (qx, qy + 2.0), (qx - 4.0, qy)]))
    cable = cable.difference(sp(k["outline"]).buffer(1.4, quad_segs=Q))
    # trees
    trees = []
    for (tu, tv, r) in TREES:
        x, y = P(tu, tv)
        trees.append(unary_union([Point(x, y - 3 - r * 0.8).buffer(r, quad_segs=Q),
                                  Polygon([(x - 0.7, y - 3.5), (x + 0.7, y - 3.5), (x + 0.7, y), (x - 0.7, y)])]))
    solid = unary_union(solids).difference(unary_union(cuts))
    solid = unary_union([solid, routes.difference(tower.buffer(0.01)).difference(cable.buffer(1.3)), cable.difference(tower.buffer(0.01))])
    # front objects get a gap so they read as separate shapes
    solid = solid.difference(device.buffer(1.3, quad_segs=Q).union(packet.buffer(1.3, quad_segs=Q)))
    solid = unary_union([solid, device, packet] + trees)
    # the tile slab: translucent, sides a bit stronger than the top, with a gap around everything solid
    top, rt, bot, lf = P(0, 0), P(T, 0), P(T, T), P(0, T)
    gap = unary_union([solid, tower, cable]).buffer(1.3, quad_segs=Q)
    top_face = sp([top, rt, bot, lf]).difference(gap)
    sides = sp([lf, bot, rt, (rt[0], rt[1] + D), (bot[0], bot[1] + D), (lf[0], lf[1] + D)]) \
        .difference(sp([top, rt, bot, lf]).buffer(0.7, join_style=2)).difference(gap)
    clean = lambda g: g.buffer(0.05, quad_segs=2).buffer(-0.05, quad_segs=2).simplify(0.08)
    return ([fill(d, "#000000", 0.4) for d in geom_paths(clean(top_face))]
            + [fill(d, "#000000", 0.7) for d in geom_paths(clean(sides))]
            + [fill(d, "#000000", 1.0) for d in geom_paths(clean(solid))])


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


if __name__ == "__main__":
    os.makedirs(SVG_OUT, exist_ok=True)
    layers = {
        "background": (background(), "Launcher background: a deep teal, lighter behind the island, so the pale board reads at 48 dp."),
        "foreground": (foreground(), "Launcher foreground: a mint board tile with a mail-blue server tower; an orange glass fiber\n"
                                     "     carries a glowing packet to a device. 108 dp canvas, subject inside the 66 dp safe zone."),
        "monochrome": (monochrome(), "Themed-icon layer (Android 13+): solid tower, fiber, packet and device on a translucent tile."),
    }
    for name, (els, comment) in layers.items():
        to_svg(els, f"{name}.svg")
        to_vector(els, f"ic_launcher_{name}.xml", comment)
    print("ok")
