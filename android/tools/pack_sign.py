#!/usr/bin/env python3
"""Align and sign an APK without the Android SDK (APK Signature Scheme v2).

    pack_sign.py unsigned.apk key.pk8 cert.pem out.apk

Does what `zipalign -P 16 4` + `apksigner sign` do, for small APKs:
uncompressed entries are aligned to 4 bytes, then a v2 signature
(RSA PKCS#1 v1.5 with SHA-256) is inserted before the central directory.
Requires the `cryptography` package.
"""
import hashlib
import struct
import sys
import zipfile

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ALG_RSA_PKCS1_SHA256 = 0x0103
V2_BLOCK_ID = 0x7109871A
MAGIC = b"APK Sig Block 42"
CHUNK = 1024 * 1024


def align(src, dst):
    """Rewrite the zip so every stored entry starts on a 4-byte boundary."""
    with zipfile.ZipFile(src) as zin, open(dst, "wb") as raw:
        with zipfile.ZipFile(raw, "w") as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                info = zipfile.ZipInfo(item.filename, date_time=(1981, 1, 1, 1, 1, 2))
                info.compress_type = item.compress_type
                info.external_attr = 0
                info.create_system = 0
                if item.compress_type == zipfile.ZIP_STORED:
                    start = raw.tell() + 30 + len(item.filename.encode())
                    info.extra = b"\0" * (-start % 4)
                zout.writestr(info, data)


def lp(data):
    """uint32 length-prefixed."""
    return struct.pack("<I", len(data)) + data


def digest_sections(sections):
    digests = []
    for section in sections:
        for i in range(0, len(section), CHUNK):
            chunk = section[i:i + CHUNK]
            digests.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(chunk)) + chunk).digest())
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(digests)) + b"".join(digests)).digest()


def sign(path, key_file, cert_file):
    with open(path, "rb") as f:
        apk = f.read()
    with open(key_file, "rb") as f:
        key = serialization.load_der_private_key(f.read(), None)
    with open(cert_file, "rb") as f:
        cert = x509.load_pem_x509_certificate(f.read())

    eocd_at = apk.rfind(b"PK\x05\x06")
    if eocd_at < 0 or eocd_at + 22 != len(apk):
        raise SystemExit("unexpected zip layout (archive comment?)")
    cd_size, cd_at = struct.unpack("<II", apk[eocd_at + 12:eocd_at + 20])
    if cd_at + cd_size != eocd_at:
        raise SystemExit("unexpected zip layout")

    entries, cd, eocd = apk[:cd_at], apk[cd_at:eocd_at], apk[eocd_at:]
    # The digest covers the EOCD as it was before the block is inserted.
    digest = digest_sections([entries, cd, eocd])

    signed_data = (
        lp(lp(struct.pack("<I", ALG_RSA_PKCS1_SHA256) + lp(digest)))
        + lp(lp(cert.public_bytes(serialization.Encoding.DER)))
        + lp(b"")
    )
    signature = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    public_key = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    signer = (
        lp(signed_data)
        + lp(lp(struct.pack("<I", ALG_RSA_PKCS1_SHA256) + lp(signature)))
        + lp(public_key)
    )
    value = lp(lp(signer))

    pair = struct.pack("<QI", 4 + len(value), V2_BLOCK_ID) + value
    size = len(pair) + 8 + len(MAGIC)
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + MAGIC

    eocd = eocd[:16] + struct.pack("<I", cd_at + len(block)) + eocd[20:]
    with open(path, "wb") as f:
        f.write(entries + block + cd + eocd)


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    unsigned, key_file, cert_file, out = sys.argv[1:]
    align(unsigned, out)
    sign(out, key_file, cert_file)
