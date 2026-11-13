"""import_packs.py <private VES/packs dir> [--dest DIR] [slot...]: copies 히릿's processed packs into packs/<slot>/.

The built-in packs are engine sounds by 히릿 (engine physics simulator), processed by ves.resynth and used with
his permission in this mod only (not MIT; see THIRD_PARTY.md). Only processed packs go into the repository:
the source folder must be a ves.resynth pack (line 1 one of PRIVATE_HEADERS), hold no models, tracks or
segment files and no WAV that pack.txt does not reference, and every referenced WAV must be RIFF PCM16 mono
22050 Hz, at most 2 s. Then pack.txt (line 1 = the built-in notice, name = the display name) and the
referenced WAVs are written to DIR/.<slot>.new and renamed to DIR/<slot>. Any refusal exits 1 before
anything is written. DIR defaults to the repository's packs/; no slot = every slot in SLOTS.
"""
import os
import re
import shutil
import struct
import sys

# (slot, source folder under the private VES/packs, display name); s2000_baked "S2000 Baked" comes later
SLOTS = [
    ('vex4', 'vex4', 'VEX 4-cyl'),
    ('vex_v10', 'vex_v10', 'VEX V10'),
    ('ae86', 'ae86', 'AE86 4A-GE'),
    ('amg_v8', 'amg_v8', 'AMG M156 V8'),
    ('gt3', 'gt3', '911 GT3 992'),
    ('gtr', 'gtr', 'GT-R VR38'),
    ('s2000', 's2000', 'S2000 F20C'),
]
PRIVATE_HEADERS = (
    '# rendered by ves.resynth (private: never commit)',
    '# converted by ves.resynth bake (private: never commit)',
)
NOTICE = ('# de22ev-ves built-in pack %s: sound by 히릿 (engine physics simulator), processed by ves.resynth; '
          'used with permission in this mod only, not MIT. See THIRD_PARTY.md.')
FORBIDDEN = ('.vesmodel', '.track.txt', '.segments.txt')
WAV_NAME = re.compile(r'^[a-z0-9_-]{1,60}\.wav$')
RATE, MAX_SECONDS = 22050, 2.0


class Refused(Exception):
    pass


def check_wav(path):
    with open(path, 'rb') as f:
        b = f.read()
    name = os.path.basename(path)
    if len(b) < 12 or b[0:4] != b'RIFF' or b[8:12] != b'WAVE':
        raise Refused('%s: not a RIFF WAVE file' % name)
    fmt, data, p = None, None, 12
    while p + 8 <= len(b):
        cid, size = b[p:p + 4], struct.unpack('<I', b[p + 4:p + 8])[0]
        if cid == b'fmt ' and size >= 16:
            fmt = struct.unpack('<HHIIHH', b[p + 8:p + 24])
        elif cid == b'data':
            data = size
        p += 8 + size + (size & 1)
    if fmt is None or data is None:
        raise Refused('%s: no fmt or data chunk' % name)
    tag, ch, rate, _, _, bits = fmt
    if tag != 1 or bits != 16 or ch != 1 or rate != RATE:
        raise Refused('%s: must be PCM16 mono %d Hz (format %d, %d bit, %d ch, %d Hz)' % (name, RATE, tag, bits, ch, rate))
    if data > MAX_SECONDS * RATE * 2:
        raise Refused('%s: longer than %g s' % (name, MAX_SECONDS))


def plan(src, slot, display):
    """checks one source folder; returns (pack.txt text, [wav names]) or raises Refused"""
    if not os.path.isfile(os.path.join(src, 'pack.txt')):
        raise Refused('%s: no pack.txt in %s' % (slot, src))
    with open(os.path.join(src, 'pack.txt'), encoding='utf-8') as f:
        lines = f.read().replace('\r\n', '\n').split('\n')
    if lines[0].strip() not in PRIVATE_HEADERS:
        raise Refused('%s: line 1 is not a ves.resynth pack header: %r' % (slot, lines[0]))
    refs = []
    for l in lines[1:]:
        if l.strip().startswith('#'):
            continue
        for tok in l.split():
            if tok.lower().endswith('.wav'):
                if not WAV_NAME.match(tok):
                    raise Refused('%s: bad file name %r' % (slot, tok))
                if tok not in refs:
                    refs.append(tok)
    if not refs:
        raise Refused('%s: pack.txt references no WAV' % slot)
    for d, dirs, files in os.walk(src):
        for n in files:
            low = n.lower()
            if low.endswith(FORBIDDEN):
                raise Refused('%s: %s must never be committed' % (slot, os.path.join(d, n)))
            if low.endswith('.wav') and (d != src or n not in refs):
                raise Refused('%s: %s is not referenced by pack.txt' % (slot, n))
    for r in refs:
        p = os.path.join(src, r)
        if not os.path.isfile(p):
            raise Refused('%s: %s missing' % (slot, r))
        check_wav(p)
    out = [NOTICE % slot]
    named = False
    for l in lines[1:]:
        if l.split()[:1] == ['name']:
            l, named = 'name ' + display, True
        out.append(l)
    if not named:
        out.insert(1, 'name ' + display)
    text = '\n'.join(out)
    if not text.endswith('\n'):
        text += '\n'
    return text, refs


def write(src, dest, slot, text, refs):
    tmp, dst, old = (os.path.join(dest, x) for x in ('.%s.new' % slot, slot, '.%s.old' % slot))
    for d in (tmp, old):
        if os.path.exists(d):
            shutil.rmtree(d)
    os.makedirs(tmp)
    with open(os.path.join(tmp, 'pack.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    for r in refs:
        shutil.copyfile(os.path.join(src, r), os.path.join(tmp, r))
    if os.path.exists(dst):
        os.rename(dst, old)
    os.rename(tmp, dst)
    if os.path.exists(old):
        shutil.rmtree(old)


def main(argv):
    args, dest = [], os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'packs')
    i = 0
    while i < len(argv):
        if argv[i] == '--dest' and i + 1 < len(argv):
            dest = argv[i + 1]
            i += 2
            continue
        args.append(argv[i])
        i += 1
    if not args:
        sys.exit(__doc__)
    root, wanted = args[0], args[1:]
    table = {s: (folder, name) for s, folder, name in SLOTS}
    slots = wanted or [s for s, _, _ in SLOTS]
    try:
        jobs = []
        for s in slots:
            if s not in table:
                raise Refused('unknown slot %s (known: %s)' % (s, ' '.join(table)))
            src = os.path.join(root, table[s][0])
            jobs.append((src, s) + plan(src, s, table[s][1]))
    except Refused as e:
        print('refused: %s' % e, file=sys.stderr)
        return 1
    os.makedirs(dest, exist_ok=True)
    for src, s, text, refs in jobs:
        write(src, dest, s, text, refs)
        print('%s: %d loops' % (s, len(refs)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
