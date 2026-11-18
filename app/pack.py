#!/usr/bin/env python3
"""Rebuild the APK: copy base.apk, add classes.dex and lib/arm64-v8a/*.so.

Why not just use System.IO.Compression (PowerShell / .NET)?
    `resources.arsc` MUST be stored with ZIP method 0 (STORED). Android 11+
    refuses to install an APK whose resource table is compressed. .NET's
    CompressionLevel.NoCompression still writes a DEFLATE stream (level 0) --
    the entry grows by a few bytes and the installer rejects it with the
    "not compatible with your device" message. Python's zipfile honours
    ZIP_STORED for real, so the packing step lives here.

usage:
    pack.py BASE DEX LIBDIR OUT     # with engine native libs
    pack.py BASE DEX OUT            # UI-only, no native libs
"""
import os
import sys
import zipfile

# entries that must never be compressed (Android's packaging rules)
MUST_STORE = {"resources.arsc"}


def main():
    if len(sys.argv) not in (4, 5):
        print("usage: pack.py BASE DEX LIBDIR OUT | BASE DEX OUT", file=sys.stderr)
        return 2
    base, dex, out = sys.argv[1], sys.argv[2], sys.argv[-1]
    libdir = sys.argv[3] if len(sys.argv) == 5 else None
    if os.path.exists(out):
        os.remove(out)

    added = []

    with zipfile.ZipFile(base) as src, \
            zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            name = info.filename
            if name == "classes.dex" or name.startswith("lib/"):
                continue
            data = src.read(name)
            store = name in MUST_STORE or info.compress_type == zipfile.ZIP_STORED
            zi = zipfile.ZipInfo(name, date_time=info.date_time)
            zi.compress_type = zipfile.ZIP_STORED if store else zipfile.ZIP_DEFLATED
            zi.external_attr = info.external_attr
            dst.writestr(zi, data)
            added.append((name, store))

        def put(name, path, store):
            with open(path, "rb") as f:
                data = f.read()
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED if store else zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            dst.writestr(zi, data)
            added.append((name, store))

        put("classes.dex", dex, False)
        if libdir:
            for fn in sorted(os.listdir(libdir)):
                if fn.endswith(".so"):
                    put("lib/arm64-v8a/" + fn, os.path.join(libdir, fn), False)

    for name, store in added:
        print("    + %-42s %s" % (name, "STORED" if store else "DEFLATE"))
    print("    packed %s (%.1f MB)" % (out, os.path.getsize(out) / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
