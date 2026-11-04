"""zipdir.py <folder> <out.zip>: a reproducible zip of a folder's contents.

Entries are sorted, carry a fixed timestamp, 0755 for *.sh and 0644 otherwise, and
UTF-8 names (the kit has a Korean file name).
"""
import os
import sys
import zipfile

STAMP = (2026, 1, 1, 0, 0, 0)


def main(src, out):
    entries = []
    for d, _, files in os.walk(src):
        for f in files:
            p = os.path.join(d, f)
            entries.append(('/'.join(os.path.relpath(p, src).split(os.sep)), p))
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, path in sorted(entries):
            info = zipfile.ZipInfo(name, STAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if name.endswith('.sh') else 0o644) << 16
            with open(path, 'rb') as fh:
                z.writestr(info, fh.read())


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
