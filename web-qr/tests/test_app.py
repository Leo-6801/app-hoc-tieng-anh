import json

from app import services


def create(client, admin, order="DH1", amount=1500000, minutes=None):
    body = {"order_code": order, "amount": amount}
    if minutes is not None:
        body["expires_in_minutes"] = minutes
    return client.post("/api/payments", json=body, headers=admin)


def event(client, reader, amount, content, **extra):
    return client.post("/api/bank-events", headers=reader,
                       json={"amount": amount, "content": content, "bank": "VCB", **extra}).json()


def token_of(p):
    return p["pay_url"].rsplit("/", 1)[-1]


# ---------------------------------------------------------------- xác thực

def test_auth_required(client, reader):
    assert client.post("/api/payments", json={"order_code": "A", "amount": 1}).status_code == 401
    assert client.get("/admin").status_code == 401
    assert client.post("/api/bank-events", json={"amount": 1}).status_code == 401
    assert client.post("/api/bank-events", json={"amount": 1},
                       headers={"X-Reader-Key": "sai"}).status_code == 401


def test_admin_basic_auth(client):
    assert client.get("/admin", auth=("admin", "admin-pass-123456")).status_code == 200
    assert client.get("/admin", auth=("admin", "sai")).status_code == 401


# ---------------------------------------------------------------- tạo mã

def test_create_is_idempotent_and_reissues_after_expiry(client, admin, clock):
    first = create(client, admin)
    assert first.status_code == 201
    again = create(client, admin)
    assert again.status_code == 200 and again.json()["ref_code"] == first.json()["ref_code"]

    clock.advance(16 * 60)
    renewed = create(client, admin)
    assert renewed.status_code == 201 and renewed.json()["ref_code"] != first.json()["ref_code"]
    old = client.get(f"/api/payments/{first.json()['ref_code']}", headers=admin).json()
    assert old["state"] == "cancelled"


def test_no_expiry_when_zero(client, admin, clock):
    p = create(client, admin, minutes=0).json()
    assert p["expires_at"] is None
    clock.advance(365 * 24 * 3600)
    assert client.get(f"/tt/{token_of(p)}/status").json()["state"] == "pending"


def test_cannot_create_for_paid_order(client, admin, reader):
    p = create(client, admin).json()
    event(client, reader, 1500000, p["ref_code"], txn_id="T1")
    assert create(client, admin).status_code == 409


# ---------------------------------------------------------------- trang thanh toán & hết hạn

def test_pay_page_active_then_expired(client, admin, clock):
    p = create(client, admin).json()
    token = token_of(p)
    page = client.get(f"/tt/{token}")
    assert page.status_code == 200
    assert "0123456789" in page.text and p["ref_code"] in page.text
    assert "dl.vietqr.io" in page.text
    assert client.get(f"/tt/{token}/vietqr.svg").status_code == 200

    clock.advance(15 * 60 + 1)
    page = client.get(f"/tt/{token}")
    assert "hết hạn" in page.text
    assert "0123456789" not in page.text          # không lộ thông tin chuyển khoản
    assert "dl.vietqr.io" not in page.text
    assert client.get(f"/tt/{token}/vietqr.svg").status_code == 410
    assert client.get(f"/tt/{token}/status").json()["state"] == "expired"


def test_printed_qr_only_contains_link(client, admin):
    p = create(client, admin).json()
    token = token_of(p)
    png = client.get(f"/qr/{token}.png")
    assert png.status_code == 200 and png.headers["content-type"] == "image/png"
    assert client.get(f"/print/{token}").status_code == 200
    assert client.get("/tt/khong-ton-tai").status_code == 404


# ---------------------------------------------------------------- khớp tiền

def test_match_and_duplicate_event(client, admin, reader):
    p = create(client, admin).json()
    r = event(client, reader, 1500000, f"MBVCB.123.{p['ref_code']}.CT tu A", txn_id="T1")
    assert r["status"] == "matched" and r["ref_code"] == p["ref_code"]
    assert client.get(f"/tt/{token_of(p)}/status").json()["state"] == "paid"
    # App đọc tin gửi lặp cùng giao dịch → bỏ qua
    assert event(client, reader, 1500000, p["ref_code"], txn_id="T1")["status"] == "duplicate_event"
    # Trang thanh toán sau khi trả: không còn thông tin chuyển khoản
    paid_page = client.get(f"/tt/{token_of(p)}").text
    assert "0123456789" not in paid_page
    assert "1.500.000đ" in paid_page and "Đơn đã thanh toán" in paid_page


def test_double_payment_goes_to_review(client, admin, reader):
    p = create(client, admin).json()
    event(client, reader, 1500000, p["ref_code"], txn_id="T1")
    r = event(client, reader, 1500000, p["ref_code"], txn_id="T2")
    assert r["status"] == "double_payment"
    review = client.get("/api/transactions", headers=admin).json()
    assert [t["match_status"] for t in review] == ["double_payment"]
    resolved = client.post(f"/api/transactions/{r['txn_id']}/resolve", headers=admin,
                           json={"resolution": "refunded", "note": "Đã hoàn"}).json()
    assert resolved["resolved"] == 1
    assert client.get("/api/transactions", headers=admin).json() == []


def test_late_payment_still_confirmed(client, admin, reader, clock):
    p = create(client, admin).json()
    clock.advance(20 * 60)
    assert event(client, reader, 1500000, p["ref_code"], txn_id="T1")["status"] == "late"
    assert client.get(f"/api/payments/{p['ref_code']}", headers=admin).json()["state"] == "paid"


def test_partial_then_rest(client, admin, reader):
    p = create(client, admin).json()
    assert event(client, reader, 500000, p["ref_code"], txn_id="T1")["status"] == "partial"
    status = client.get(f"/tt/{token_of(p)}/status").json()
    assert status["state"] == "partial" and status["remaining"] == 1000000
    assert event(client, reader, 1000000, p["ref_code"], txn_id="T2")["status"] == "matched"


def test_overpaid(client, admin, reader):
    p = create(client, admin).json()
    r = event(client, reader, 1600000, p["ref_code"], txn_id="T1")
    assert r["status"] == "overpaid" and r["excess"] == 100000
    assert client.get(f"/api/payments/{p['ref_code']}", headers=admin).json()["state"] == "paid"


def test_unmatched_then_manual_assign(client, admin, reader):
    p = create(client, admin).json()
    r = event(client, reader, 1500000, "chuyen tien cho shop", txn_id="T1")
    assert r["status"] == "unmatched"
    a = client.post(f"/api/transactions/{r['txn_id']}/assign", headers=admin,
                    json={"ref_code": p["ref_code"]}).json()
    assert a["status"] == "matched" and a["payment"]["state"] == "paid"
    assert client.get("/api/transactions", headers=admin).json() == []


def test_cancelled_order_and_outgoing(client, admin, reader):
    p = create(client, admin).json()
    client.post(f"/api/payments/{p['ref_code']}/cancel", headers=admin)
    assert event(client, reader, 1500000, p["ref_code"], txn_id="T1")["status"] == "inactive_payment"
    assert event(client, reader, 50000, "rut tien", direction="out", txn_id="T2")["status"] == "ignored"


def test_erp_callback_signed(client, admin, reader, settings, monkeypatch):
    sent = {}

    class Resp:
        def raise_for_status(self):
            pass

    def fake_post(url, content, headers, timeout):
        sent.update(url=url, body=content, headers=headers)
        return Resp()

    import httpx
    monkeypatch.setattr(httpx, "post", fake_post)
    settings.erp_callback_url = "https://erp.example.vn/hook"
    settings.erp_callback_secret = "erp-secret"

    p = create(client, admin, order="DH9").json()
    event(client, reader, 1500000, p["ref_code"], txn_id="T1")
    body = json.loads(sent["body"])
    assert body["order_code"] == "DH9" and body["paid_amount"] == 1500000
    assert sent["headers"]["X-Signature"] == services.sign(sent["body"], "erp-secret")
    assert client.get(f"/api/payments/{p['ref_code']}", headers=admin).json()["erp_notified"] is True
