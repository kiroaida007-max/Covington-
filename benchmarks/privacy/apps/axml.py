"""Minimal reader of an APK's binary AndroidManifest.xml: the permissions it requests (uses-permission)."""
import struct
import zipfile


def _strings(b, off):
    _, header, size = struct.unpack_from("<HHI", b, off)
    count, _, flags, start, _ = struct.unpack_from("<IIIII", b, off + 8)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from("<%dI" % count, b, off + header)
    out = []
    for o in offsets:
        p = off + start + o
        if utf8:
            n = b[p]; p += 2 if n & 0x80 else 1          # UTF-16 length (skipped)
            n = b[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | b[p + 1]; p += 2
            else:
                p += 1
            out.append(b[p:p + n].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", b, p)[0]; p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", b, p)[0]; p += 2
            out.append(b[p:p + 2 * n].decode("utf-16-le", "replace"))
    return out, off + size


def permissions(apk):
    """The `android:name` of every <uses-permission> (and -sdk-23) element."""
    b = zipfile.ZipFile(apk).read("AndroidManifest.xml")
    off = struct.unpack_from("<HHI", b, 0)[1]
    strings, off = [], off
    out = []
    while off < len(b):
        typ, header, size = struct.unpack_from("<HHI", b, off)
        if typ == 0x0001:
            strings, _ = _strings(b, off)
        elif typ == 0x0102:
            name_idx = struct.unpack_from("<I", b, off + 20)[0]
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", b, off + 24)
            tag = strings[name_idx] if name_idx < len(strings) else ""
            if tag in ("uses-permission", "uses-permission-sdk-23"):
                for i in range(attr_count):
                    a = off + 16 + attr_start + i * attr_size
                    _, aname, raw = struct.unpack_from("<III", b, a)
                    if aname < len(strings) and strings[aname] == "name" and raw != 0xFFFFFFFF:
                        out.append(strings[raw])
        if size == 0:
            break
        off += size
    return sorted(set(out))
