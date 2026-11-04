"""make_icon.py <out.png> <size>: the app icon / launcher tile, drawn here (no image files in git).

A rev-counter: dark disc, an arc of ticks, a red zone and a needle; anti-aliased by
4x4 supersampling. Plain Python (zlib), no imaging library needed.
"""
import math
import struct
import sys
import zlib


def render(n):
    ss = 4
    cx = cy = n / 2.0
    r_disc = n * 0.47
    r_arc_o, r_arc_i = n * 0.40, n * 0.33
    start, sweep = math.radians(225), math.radians(270)  # 225 deg -> -45 deg, clockwise
    needle = math.radians(225 - 270 * 0.62)
    rows = []
    for y in range(n):
        row = bytearray()
        for x in range(n):
            acc = [0.0, 0.0, 0.0, 0.0]
            for sy in range(ss):
                for sx in range(ss):
                    px = x + (sx + 0.5) / ss - cx
                    py = cy - (y + (sy + 0.5) / ss)
                    d = math.hypot(px, py)
                    c = None
                    if d <= r_disc:
                        c = (22, 24, 30)
                        ang = math.atan2(py, px)
                        rel = (start - ang) % (2 * math.pi)
                        if r_arc_i <= d <= r_arc_o and rel <= sweep:
                            frac = rel / sweep
                            tick = (frac * 10) % 1.0
                            if frac > 0.78:
                                c = (230, 60, 50)
                            elif tick < 0.18 or d > r_arc_o - n * 0.025:
                                c = (240, 240, 240)
                        # needle: a thin wedge from the centre
                        nx, ny = math.cos(needle), math.sin(needle)
                        along = px * nx + py * ny
                        across = abs(-px * ny + py * nx)
                        if -n * 0.04 <= along <= n * 0.36 and across <= n * 0.022 * (1 - along / (n * 0.42)):
                            c = (255, 150, 30)
                        if d <= n * 0.055:
                            c = (255, 150, 30)
                    if c is not None:
                        acc[0] += c[0]
                        acc[1] += c[1]
                        acc[2] += c[2]
                        acc[3] += 255
            k = ss * ss
            a = acc[3] / k
            if a > 0:
                row += bytes((int(acc[0] / k * 255 / a + 0.5) if a else 0,
                              int(acc[1] / k * 255 / a + 0.5) if a else 0,
                              int(acc[2] / k * 255 / a + 0.5) if a else 0,
                              int(a + 0.5)))
            else:
                row += b'\0\0\0\0'
        rows.append(bytes(row))
    return rows


def png(rows, n):
    raw = b''.join(b'\0' + r for r in rows)

    def chunk(t, data):
        return struct.pack('>I', len(data)) + t + data + struct.pack('>I', zlib.crc32(t + data) & 0xffffffff)

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', n, n, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    size = int(sys.argv[2])
    with open(sys.argv[1], 'wb') as f:
        f.write(png(render(size), size))
