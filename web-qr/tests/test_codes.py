from app import refcode, vietqr


def test_crc16_check_value():
    # Giá trị kiểm tra chuẩn của CRC-16/CCITT-FALSE
    assert vietqr.crc16_ccitt(b"123456789") == 0x29B1


def test_vietqr_payload_structure():
    payload = vietqr.build_payload("970436", "0123456789", 1500000, "PL7K3M9QX")
    assert vietqr.verify_crc(payload)
    top = vietqr.parse_tlv(payload)
    assert top["00"] == "01"
    assert top["01"] == "12"  # QR động
    assert top["53"] == "704"
    assert top["54"] == "1500000"
    assert top["58"] == "VN"
    merchant = vietqr.parse_tlv(top["38"])
    assert merchant["00"] == "A000000727"
    assert merchant["02"] == "QRIBFTTA"
    assert vietqr.parse_tlv(merchant["01"]) == {"00": "970436", "01": "0123456789"}
    assert vietqr.parse_tlv(top["62"]) == {"08": "PL7K3M9QX"}


def test_refcode_generate_and_validate():
    for _ in range(200):
        code = refcode.generate("PL")
        assert len(code) == 9 and refcode.is_valid(code, "PL")


def test_refcode_detects_single_errors_and_swaps():
    code = refcode.generate("PL")
    body = code[2:]
    for i in range(len(body)):
        for ch in refcode.ALPHABET:
            if ch != body[i]:
                assert not refcode.is_valid("PL" + body[:i] + ch + body[i + 1:], "PL")
    for i in range(len(body) - 1):
        if body[i] != body[i + 1]:
            swapped = body[:i] + body[i + 1] + body[i] + body[i + 2:]
            assert not refcode.is_valid("PL" + swapped, "PL")


def test_find_codes_in_messy_bank_content():
    code = refcode.generate("PL")
    for text in (
        f"MBVCB.3829102.{code.lower()}.CT tu 0123 NGUYEN VAN A",
        f"{code}chuyen tien",
        f"Chuyển khoản {code} đơn hàng",
        f"APPLE PLXX {code}",
    ):
        assert refcode.find_codes(text, "PL") == [code]
    assert refcode.find_codes("chuyen tien an trua", "PL") == []
