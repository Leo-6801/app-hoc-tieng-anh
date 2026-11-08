"""Mã nhận diện đặt trong nội dung chuyển khoản.

Cấu trúc: TIỀN TỐ + 6 ký tự ngẫu nhiên + 1 ký tự kiểm tra, ví dụ PL7K3M9QX.
- Chỉ chữ IN HOA và số, bỏ các ký tự dễ nhầm 0/O, 1/I/L.
- Ký tự kiểm tra (trọng số 1..6, modulo 31 — số nguyên tố) phát hiện được
  mọi lỗi sai 1 ký tự và mọi lỗi đảo 2 ký tự liền nhau.
- Khi đọc: quét toàn bộ nội dung (ngân hàng hay thêm chữ trước/sau), không đọc theo vị trí.
"""
from __future__ import annotations

import re
import secrets
import unicodedata

ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"  # 31 ký tự
BODY_LENGTH = 6


def _check_char(body: str) -> str:
    total = sum((i + 1) * ALPHABET.index(ch) for i, ch in enumerate(body))
    return ALPHABET[total % len(ALPHABET)]


def generate(prefix: str) -> str:
    body = "".join(secrets.choice(ALPHABET) for _ in range(BODY_LENGTH))
    return prefix + body + _check_char(body)


def is_valid(code: str, prefix: str) -> bool:
    if len(code) != len(prefix) + BODY_LENGTH + 1 or not code.startswith(prefix):
        return False
    body, check = code[len(prefix):-1], code[-1]
    if any(ch not in ALPHABET for ch in body + check):
        return False
    return _check_char(body) == check


def normalize(text: str) -> str:
    """Bỏ dấu tiếng Việt, đổi sang IN HOA."""
    text = text.replace("đ", "d").replace("Đ", "D")
    text = unicodedata.normalize("NFKD", text)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    return text.upper()


def find_codes(text: str, prefix: str) -> list[str]:
    """Tìm mọi mã hợp lệ (đúng ký tự kiểm tra) trong nội dung, giữ thứ tự xuất hiện."""
    pattern = re.compile(
        "(?=(" + re.escape(prefix) + "[" + ALPHABET + "]{" + str(BODY_LENGTH + 1) + "}))"
    )
    found: list[str] = []
    for match in pattern.finditer(normalize(text)):
        code = match.group(1)
        if is_valid(code, prefix) and code not in found:
            found.append(code)
    return found
