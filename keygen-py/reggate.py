"""RegGate 注册机核心协议（与 Android 端 registration-lib / keygen-app 一致）。

协议概要
--------
安装码 (客户机 → 注册机):
    V2: 0x01 || XOR(nonce[8], fixed_ks[0:7]) || XOR(deviceId[12] || pkgLen[2] || pkg, nonce_ks)
    V1: deviceId[12] || nonce[8] [|| pkgLen[2] || pkg]   (直通, 向后兼容)

激活码 (注册机 → 客户机):
    Base32( XOR(validDays[2] || issuedDay[4] || sig[256], keystream[262]) )
      - keystream = SHA-256 CTR(deviceId || nonce [|| pkgLen || pkg]) 生成 262 字节
      - sig = SHA256withRSA 签名(私钥), 覆盖:
            deviceId[12] || nonce[8] || validDays[2] || issuedDay[4] [|| pkgLen[2] || pkg]
"""

from __future__ import annotations

import base64
import hashlib
import re
import time

# ===== 常量 =====
DEVICE_ID_LEN = 12
NONCE_LEN = 8
VALID_DAYS_LEN = 2
ISSUED_DAY_LEN = 4
SIG_LEN = 256
DAY_MS = 24 * 60 * 60 * 1000

# 安装码 XOR 加扰固定密钥: SHA-256("RegGate.Request.ScrambleKey.v1")
REQUEST_SCRAMBLE_KEY = hashlib.sha256(b"RegGate.Request.ScrambleKey.v1").digest()


# ===== Crockford Base32 =====
class Base32:
    ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    _DECODE = {}
    for _i, _c in enumerate(ALPHABET):
        _DECODE[_c] = _i
        _DECODE[_c.lower()] = _i
    # Crockford 兼容映射: O/o -> 0, I/i/L/l -> 1
    _DECODE["O"] = 0
    _DECODE["o"] = 0
    _DECODE["I"] = 1
    _DECODE["i"] = 1
    _DECODE["L"] = 1
    _DECODE["l"] = 1

    @classmethod
    def encode(cls, data: bytes) -> str:
        if not data:
            return ""
        out = []
        buffer = 0
        bits_left = 0
        for b in data:
            buffer = (buffer << 8) | (b & 0xFF)
            bits_left += 8
            while bits_left >= 5:
                idx = (buffer >> (bits_left - 5)) & 0x1F
                out.append(cls.ALPHABET[idx])
                bits_left -= 5
        if bits_left > 0:
            idx = (buffer << (5 - bits_left)) & 0x1F
            out.append(cls.ALPHABET[idx])
        return "".join(out)

    @classmethod
    def decode(cls, s: str | None) -> bytes | None:
        if s is None:
            return None
        clean = s.replace("-", " ")
        clean = "".join(clean.split()).upper()
        if clean == "":
            return b""
        out = bytearray(len(clean) * 5 // 8)
        buffer = 0
        bits_left = 0
        idx = 0
        for ch in clean:
            v = cls._DECODE.get(ch, -1)
            if v < 0:
                return None
            buffer = (buffer << 5) | v
            bits_left += 5
            if bits_left >= 8:
                out[idx] = (buffer >> (bits_left - 8)) & 0xFF
                idx += 1
                bits_left -= 8
        return bytes(out[:idx])

    @classmethod
    def group(cls, s: str, group_size: int = 5) -> str:
        if s is None:
            return None
        out = []
        for i, ch in enumerate(s):
            if i > 0 and i % group_size == 0:
                out.append("-")
            out.append(ch)
        return "".join(out)

    @classmethod
    def ungroup(cls, s: str | None) -> str | None:
        if s is None:
            return None
        return re.sub(r"[\s-]", "", s).upper()


# ===== 密钥流派生 (SHA-256 CTR) =====
def _sha256_ctr(seed_parts: list[bytes], length: int) -> bytes:
    keystream = bytearray(length)
    for block in range(0, length, 32):
        h = hashlib.sha256()
        for part in seed_parts:
            h.update(part)
        h.update(bytes([(block >> 24) & 0xFF]))
        h.update(bytes([(block >> 16) & 0xFF]))
        h.update(bytes([(block >> 8) & 0xFF]))
        h.update(bytes([block & 0xFF]))
        digest = h.digest()
        copy_len = min(32, length - block)
        keystream[block:block + copy_len] = digest[:copy_len]
    return bytes(keystream)


def derive_keystream(device_id: bytes, nonce: bytes, pkg_bytes: bytes | None, length: int) -> bytes:
    """激活码密钥流: SHA-256 CTR(deviceId || nonce [|| pkgLen || pkg])。"""
    seed = [device_id, nonce]
    if pkg_bytes:
        seed.append(bytes([(len(pkg_bytes) >> 8) & 0xFF, len(pkg_bytes) & 0xFF]))
        seed.append(pkg_bytes)
    return _sha256_ctr(seed, length)


def derive_request_keystream(length: int, nonce: bytes | None = None) -> bytes:
    """安装码密钥流: SHA-256 CTR(REQUEST_SCRAMBLE_KEY [|| nonce])。"""
    seed = [REQUEST_SCRAMBLE_KEY]
    if nonce:
        seed.append(nonce)
    return _sha256_ctr(seed, length)


# ===== 安装码解析 =====
def parse_request_code(request_code: str) -> tuple[bytes, bytes, bytes] | None:
    """返回 (device_id, nonce, pkg_bytes)。格式错误返回 None。"""
    raw = Base32.decode(request_code)
    if raw is None or len(raw) < 1:
        return None

    if raw[0] == 0x01:
        # V2: 两步解扰
        data = raw[1:]
        if len(data) < NONCE_LEN + DEVICE_ID_LEN:
            return None
        fixed_ks = derive_request_keystream(len(data))
        nonce = bytes(data[i] ^ fixed_ks[i] for i in range(NONCE_LEN))
        rest_len = len(data) - NONCE_LEN
        nonce_ks = derive_request_keystream(rest_len, nonce)
        rest = bytes(data[NONCE_LEN + i] ^ nonce_ks[i] for i in range(rest_len))
        if len(rest) < DEVICE_ID_LEN:
            return None
        device_id = rest[:DEVICE_ID_LEN]
        pkg_bytes = _extract_pkg(rest, DEVICE_ID_LEN)
        return device_id, nonce, pkg_bytes
    else:
        # V1: 直通
        data = raw
        if len(data) < DEVICE_ID_LEN + NONCE_LEN:
            return None
        device_id = data[:DEVICE_ID_LEN]
        nonce = data[DEVICE_ID_LEN:DEVICE_ID_LEN + NONCE_LEN]
        pkg_bytes = _extract_pkg(data, DEVICE_ID_LEN + NONCE_LEN)
        return device_id, nonce, pkg_bytes


def _extract_pkg(buf: bytes, off: int) -> bytes:
    """从 off 处读取 pkgLen[2] || pkg。"""
    if len(buf) >= off + 2:
        pkg_len = ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF)
        if pkg_len > 0 and len(buf) >= off + 2 + pkg_len:
            return buf[off + 2:off + 2 + pkg_len]
    return b""


def extract_package_name(request_code: str) -> str | None:
    parsed = parse_request_code(request_code)
    if parsed is None:
        return None
    pkg_bytes = parsed[2]
    if not pkg_bytes:
        return ""
    return pkg_bytes.decode("utf-8")


# ===== 私钥加载 =====
def parse_private_key(content: str):
    from cryptography.hazmat.primitives.serialization import (
        load_pem_private_key,
        load_der_private_key,
    )
    # 先尝试 PEM
    try:
        return load_pem_private_key(content.encode(), password=None)
    except Exception:
        pass
    # 去掉 PEM 头尾后按 DER 解析
    cleaned = re.sub(r"-----[^-]+-----", "", content)
    cleaned = re.sub(r"\s+", "", cleaned)
    raw = base64.b64decode(cleaned)
    return load_der_private_key(raw, password=None)


# ===== 签名消息构建 =====
def build_signed_message(device_id: bytes, nonce: bytes, pkg_bytes: bytes,
                         valid_days: int, issued_day: int) -> bytes:
    has_pkg = bool(pkg_bytes)
    pkg_part = (2 + len(pkg_bytes)) if has_pkg else 0
    msg = bytearray(DEVICE_ID_LEN + NONCE_LEN + VALID_DAYS_LEN + ISSUED_DAY_LEN + pkg_part)
    msg[0:DEVICE_ID_LEN] = device_id
    msg[DEVICE_ID_LEN:DEVICE_ID_LEN + NONCE_LEN] = nonce
    off = DEVICE_ID_LEN + NONCE_LEN
    msg[off] = (valid_days >> 8) & 0xFF; off += 1
    msg[off] = valid_days & 0xFF; off += 1
    msg[off] = (issued_day >> 24) & 0xFF; off += 1
    msg[off] = (issued_day >> 16) & 0xFF; off += 1
    msg[off] = (issued_day >> 8) & 0xFF; off += 1
    msg[off] = issued_day & 0xFF; off += 1
    if has_pkg:
        msg[off] = (len(pkg_bytes) >> 8) & 0xFF; off += 1
        msg[off] = len(pkg_bytes) & 0xFF; off += 1
        msg[off:off + len(pkg_bytes)] = pkg_bytes
    return bytes(msg)


# ===== 生成激活码 =====
def generate_activation_code(request_code: str, valid_days: int, priv) -> str:
    parsed = parse_request_code(request_code)
    if parsed is None:
        raise ValueError("安装码格式错误")
    device_id, nonce, pkg_bytes = parsed

    issued_day = int(time.time() * 1000) // DAY_MS
    msg = build_signed_message(device_id, nonce, pkg_bytes, valid_days, issued_day)

    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    sig = priv.sign(msg, padding.PKCS1v15(), hashes.SHA256())

    out = bytearray(VALID_DAYS_LEN + ISSUED_DAY_LEN + SIG_LEN)
    out[0] = (valid_days >> 8) & 0xFF
    out[1] = valid_days & 0xFF
    out[2] = (issued_day >> 24) & 0xFF
    out[3] = (issued_day >> 16) & 0xFF
    out[4] = (issued_day >> 8) & 0xFF
    out[5] = issued_day & 0xFF
    out[6:] = sig

    keystream = derive_keystream(device_id, nonce, pkg_bytes, len(out))
    out = bytes(out[i] ^ keystream[i] for i in range(len(out)))
    return Base32.group(Base32.encode(out))


# ===== 验签 (用于自测, 与注册库一致) =====
def verify_activation_code(activation_code: str, pub, expected_device_id: bytes,
                           expected_nonce: bytes, expected_pkg_bytes: bytes) -> dict | None:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding

    data = Base32.decode(activation_code)
    if data is None:
        return None
    expected_len = VALID_DAYS_LEN + ISSUED_DAY_LEN + SIG_LEN
    if len(data) != expected_len:
        return None

    keystream = derive_keystream(expected_device_id, expected_nonce, expected_pkg_bytes, len(data))
    plain = bytes(data[i] ^ keystream[i] for i in range(len(data)))

    valid_days = ((plain[0] & 0xFF) << 8) | (plain[1] & 0xFF)
    issued_day = ((plain[2] & 0xFF) << 24) | ((plain[3] & 0xFF) << 16) | \
                 ((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF)
    sig = plain[VALID_DAYS_LEN + ISSUED_DAY_LEN:]

    msg = build_signed_message(expected_device_id, expected_nonce, expected_pkg_bytes, valid_days, issued_day)
    try:
        pub.verify(sig, msg, padding.PKCS1v15(), hashes.SHA256())
    except Exception:
        return None

    issued_ms = issued_day * DAY_MS
    expiry_ms = 0 if valid_days == 0 else (issued_day + valid_days) * DAY_MS
    return {
        "valid_days": valid_days,
        "issued_day": issued_day,
        "issued_ms": issued_ms,
        "expiry_ms": expiry_ms,
    }


# ===== 构造 V2 安装码 (用于自测) =====
def build_request_code_v2(device_id: bytes, nonce: bytes, pkg_bytes: bytes = b"") -> str:
    rest = bytearray(device_id)
    if pkg_bytes:
        rest += bytes([(len(pkg_bytes) >> 8) & 0xFF, len(pkg_bytes) & 0xFF])
        rest += pkg_bytes
    rest = bytes(rest)

    nonce_ks = derive_request_keystream(len(rest), nonce)
    scrambled_rest = bytes(rest[i] ^ nonce_ks[i] for i in range(len(rest)))
    fixed_ks = derive_request_keystream(NONCE_LEN + len(scrambled_rest))
    scrambled_nonce = bytes(nonce[i] ^ fixed_ks[i] for i in range(NONCE_LEN))

    payload = b"\x01" + scrambled_nonce + scrambled_rest
    return Base32.group(Base32.encode(payload))


def format_expiry(valid_days: int) -> str:
    if valid_days <= 0:
        return "永久"
    issued_day = int(time.time() * 1000) // DAY_MS
    exp = (issued_day + valid_days) * DAY_MS
    return time.strftime("%Y-%m-%d", time.localtime(exp / 1000.0))


if __name__ == "__main__":
    # 简单自测: 生成密钥对 -> 构造安装码 -> 生成激活码 -> 验签
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization

    priv_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pub_key = priv_key.public_key()

    dev = bytes(range(12))
    nc = bytes(range(8, 16))
    pkg = b"com.example.chees"

    req = build_request_code_v2(dev, nc, pkg)
    code = generate_activation_code(req, 365, priv_key)
    res = verify_activation_code(code, pub_key, dev, nc, pkg)

    assert res is not None, "验签失败"
    assert res["valid_days"] == 365, res
    # 包名绑定: 用错误包名应验签失败
    assert verify_activation_code(code, pub_key, dev, nc, b"com.other.app") is None
    print("自测通过: 安装码 / 激活码 / 验签 / 包绑定 均正确")
    print("  安装码:", req)
    print("  激活码:", code)
    print("  有效期:", res["valid_days"], "天")
