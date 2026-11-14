"""Tạo chuỗi VietQR (chuẩn EMVCo của NAPAS) — tự tạo, không gọi API bên ngoài."""
from __future__ import annotations

import re

NAPAS_GUID = "A000000727"
SERVICE_TRANSFER_TO_ACCOUNT = "QRIBFTTA"


def crc16_ccitt(data: bytes) -> int:
    """CRC-16/CCITT-FALSE (đa thức 0x1021, giá trị đầu 0xFFFF) theo chuẩn EMVCo."""
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else (crc << 1)
            crc &= 0xFFFF
    return crc


def _tlv(tag: str, value: str) -> str:
    if len(value) > 99:
        raise ValueError(f"Trường {tag} quá dài")
    return f"{tag}{len(value):02d}{value}"


def sanitize_content(content: str) -> str:
    """Nội dung chuyển khoản: chỉ chữ/số không dấu và khoảng trắng, tối đa 25 ký tự."""
    cleaned = re.sub(r"[^0-9A-Za-z ]", "", content).strip()
    if not cleaned:
        raise ValueError("Nội dung chuyển khoản trống")
    return cleaned[:25]


def build_payload(bank_bin: str, account_number: str, amount: int, content: str) -> str:
    if amount <= 0:
        raise ValueError("Số tiền phải lớn hơn 0")
    beneficiary = _tlv("00", bank_bin) + _tlv("01", account_number)
    merchant = (
        _tlv("00", NAPAS_GUID)
        + _tlv("01", beneficiary)
        + _tlv("02", SERVICE_TRANSFER_TO_ACCOUNT)
    )
    payload = (
        _tlv("00", "01")
        + _tlv("01", "12")  # 12 = QR động (mỗi đơn một mã)
        + _tlv("38", merchant)
        + _tlv("53", "704")  # VND
        + _tlv("54", str(int(amount)))
        + _tlv("58", "VN")
        + _tlv("62", _tlv("08", sanitize_content(content)))
        + "6304"
    )
    return payload + f"{crc16_ccitt(payload.encode('ascii')):04X}"


def parse_tlv(payload: str) -> dict[str, str]:
    """Tách chuỗi TLV một cấp (dùng để kiểm tra)."""
    result, i = {}, 0
    while i < len(payload):
        tag, length = payload[i:i + 2], int(payload[i + 2:i + 4])
        result[tag] = payload[i + 4:i + 4 + length]
        i += 4 + length
    return result


def verify_crc(payload: str) -> bool:
    body, crc = payload[:-4], payload[-4:]
    return f"{crc16_ccitt(body.encode('ascii')):04X}" == crc.upper()
