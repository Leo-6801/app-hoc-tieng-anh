"""Nghiệp vụ: tạo mã thanh toán, khớp giao dịch ngân hàng, báo về ERP."""
from __future__ import annotations

import hashlib
import hmac
import json
import secrets
from dataclasses import dataclass
from typing import Any

from . import refcode
from .config import Settings
from .db import Database, now

ACTIVE_STATUSES = ("pending", "partial")
MAX_EXPIRE_MINUTES = 60 * 24 * 30  # 30 ngày

# Kết quả khớp giao dịch. RESOLVED_OK: không cần người xử lý.
MATCHED = "matched"            # đúng mã, đủ tiền, còn hạn
LATE = "late"                  # đúng mã, đủ tiền, nhưng trả sau hạn (vẫn xác nhận)
PARTIAL = "partial"            # trả thiếu
OVERPAID = "overpaid"          # trả thừa (đơn vẫn thành "đã thanh toán")
DOUBLE_PAYMENT = "double_payment"  # đơn đã trả đủ trước đó → chuyển 2 lần
INACTIVE_PAYMENT = "inactive_payment"  # mã thuộc đơn đã hủy/đóng
UNMATCHED = "unmatched"        # không tìm thấy mã hợp lệ
IGNORED = "ignored"            # tiền ra, không xét
MANUAL = "manual"              # nhân viên ghép tay
RESOLVED_OK = {MATCHED, LATE, IGNORED, MANUAL}


class ServiceError(Exception):
    def __init__(self, message: str, status_code: int = 400):
        super().__init__(message)
        self.message = message
        self.status_code = status_code


# ---------------------------------------------------------------- mã thanh toán

def effective_state(payment: dict[str, Any], at: int | None = None) -> str:
    """Trạng thái hiển thị: thêm 'expired' cho đơn đang chờ đã quá hạn."""
    at = now() if at is None else at
    if payment["status"] in ACTIVE_STATUSES and payment["expires_at"] and at > payment["expires_at"]:
        return "expired"
    return payment["status"]


def remaining_amount(payment: dict[str, Any]) -> int:
    return max(payment["amount"] - payment["paid_amount"], 0)


def get_payment_by_ref(db: Database, ref_code: str) -> dict[str, Any]:
    payment = db.one("SELECT * FROM payments WHERE ref_code = ?", (ref_code.upper(),))
    if not payment:
        raise ServiceError("Không tìm thấy mã thanh toán", 404)
    return payment


def get_payment_by_token(db: Database, token: str) -> dict[str, Any] | None:
    return db.one("SELECT * FROM payments WHERE token = ?", (token,))


def create_payment(
    db: Database,
    settings: Settings,
    order_code: str,
    amount: int,
    expires_in_minutes: int | None = None,
    note: str = "",
    actor: str = "api",
) -> tuple[dict[str, Any], bool]:
    """Tạo mã cho đơn. Trả về (payment, created). Một đơn chỉ có một mã đang hiệu lực."""
    order_code = (order_code or "").strip()
    if not order_code or len(order_code) > 64:
        raise ServiceError("Mã đơn không hợp lệ (1–64 ký tự)")
    if not isinstance(amount, int) or amount <= 0 or amount > 10**12:
        raise ServiceError("Số tiền không hợp lệ")
    if expires_in_minutes is None:
        expires_in_minutes = settings.default_expire_minutes
    if expires_in_minutes < 0 or expires_in_minutes > MAX_EXPIRE_MINUTES:
        raise ServiceError(f"Thời hạn phải từ 0 đến {MAX_EXPIRE_MINUTES} phút (0 = không hết hạn)")

    with db.lock:
        current = now()
        if db.one("SELECT id FROM payments WHERE order_code = ? AND status = 'paid'", (order_code,)):
            raise ServiceError("Đơn này đã thanh toán, không tạo mã mới", 409)

        for existing in db.all(
            "SELECT * FROM payments WHERE order_code = ? AND status IN ('pending','partial') ORDER BY id DESC",
            (order_code,),
        ):
            still_valid = effective_state(existing, current) != "expired"
            if still_valid and existing["amount"] == amount:
                return existing, False  # mở lại → trả đúng mã cũ, không tạo mã mới
            # Mã cũ hết hạn hoặc số tiền đơn đã đổi → đóng mã cũ.
            # Tiền về sau này cho mã cũ sẽ được đưa vào danh sách cần xử lý.
            new_status = "closed" if existing["paid_amount"] > 0 else "cancelled"
            db.execute("UPDATE payments SET status = ? WHERE id = ?", (new_status, existing["id"]))
            db.log(actor, f"auto_{new_status}", payment_id=existing["id"],
                   detail="Tạo mã mới cho cùng đơn")

        expires_at = current + expires_in_minutes * 60 if expires_in_minutes else None
        for _ in range(10):
            ref = refcode.generate(settings.ref_prefix)
            if not db.one("SELECT id FROM payments WHERE ref_code = ?", (ref,)):
                break
        else:  # pragma: no cover
            raise ServiceError("Không tạo được mã nhận diện, thử lại", 500)

        cur = db.execute(
            "INSERT INTO payments (token, ref_code, order_code, amount, note, created_at, expires_at)"
            " VALUES (?,?,?,?,?,?,?)",
            (secrets.token_urlsafe(18), ref, order_code, amount, note[:500], current, expires_at),
        )
        db.log(actor, "create", payment_id=cur.lastrowid,
               detail=f"order={order_code} amount={amount} expires_in={expires_in_minutes}")
        return db.one("SELECT * FROM payments WHERE id = ?", (cur.lastrowid,)), True


def cancel_payment(db: Database, ref_code: str, actor: str) -> dict[str, Any]:
    with db.lock:
        payment = get_payment_by_ref(db, ref_code)
        if payment["status"] not in ACTIVE_STATUSES:
            raise ServiceError("Chỉ hủy được mã đang chờ thanh toán", 409)
        new_status = "closed" if payment["paid_amount"] > 0 else "cancelled"
        db.execute("UPDATE payments SET status = ? WHERE id = ?", (new_status, payment["id"]))
        db.log(actor, new_status, payment_id=payment["id"])
        return db.one("SELECT * FROM payments WHERE id = ?", (payment["id"],))


# ---------------------------------------------------------------- giao dịch ngân hàng

@dataclass
class BankEvent:
    amount: int
    direction: str = "in"
    content: str = ""
    bank: str = ""
    txn_id: str = ""
    balance: int | None = None
    txn_time: int | None = None
    raw: str = ""

    def key(self) -> str:
        """Khóa chống trùng. Có mã giao dịch ngân hàng thì dùng; không thì băm các trường.
        Số dư sau giao dịch giúp phân biệt 2 lần chuyển giống hệt nhau."""
        if self.txn_id:
            return f"{self.bank}:{self.txn_id}"
        basis = "|".join(str(x) for x in (self.bank, self.amount, self.direction,
                                           self.content, self.txn_time, self.balance))
        return "h:" + hashlib.sha256(basis.encode()).hexdigest()


@dataclass
class ProcessResult:
    status: str
    txn_id: int | None = None
    payment: dict[str, Any] | None = None
    newly_paid: bool = False
    excess: int = 0


def _apply_money(db: Database, payment: dict[str, Any], amount: int, paid_time: int) -> tuple[str, bool, int]:
    """Cộng tiền vào đơn đang chờ. Trả về (kết quả, vừa thành 'đã trả', tiền thừa)."""
    new_paid = payment["paid_amount"] + amount
    late = bool(payment["expires_at"]) and paid_time > payment["expires_at"]
    if new_paid < payment["amount"]:
        db.execute("UPDATE payments SET paid_amount = ?, status = 'partial' WHERE id = ?",
                   (new_paid, payment["id"]))
        return PARTIAL, False, 0
    db.execute("UPDATE payments SET paid_amount = ?, status = 'paid', paid_at = ? WHERE id = ?",
               (new_paid, paid_time, payment["id"]))
    if new_paid > payment["amount"]:
        return OVERPAID, True, new_paid - payment["amount"]
    return (LATE if late else MATCHED), True, 0


def process_bank_event(db: Database, settings: Settings, event: BankEvent) -> ProcessResult:
    if event.amount <= 0:
        raise ServiceError("Số tiền phải lớn hơn 0")
    if event.direction not in ("in", "out"):
        raise ServiceError("direction phải là 'in' hoặc 'out'")

    with db.lock:
        received = now()
        key = event.key()
        existing = db.one("SELECT id, match_status FROM bank_txns WHERE txn_key = ?", (key,))
        if existing:
            return ProcessResult(status="duplicate_event", txn_id=existing["id"])

        payment, status, newly_paid, excess = None, UNMATCHED, False, 0
        if event.direction == "out":
            status = IGNORED
        else:
            for code in refcode.find_codes(event.content, settings.ref_prefix):
                payment = db.one("SELECT * FROM payments WHERE ref_code = ?", (code,))
                if payment:
                    break
            if payment:
                if payment["status"] == "paid":
                    status = DOUBLE_PAYMENT
                elif payment["status"] in ACTIVE_STATUSES:
                    status, newly_paid, excess = _apply_money(
                        db, payment, event.amount, event.txn_time or received)
                else:
                    status = INACTIVE_PAYMENT

        cur = db.execute(
            "INSERT INTO bank_txns (txn_key, bank, amount, direction, content, balance, txn_time,"
            " received_at, raw, match_status, payment_id, resolved) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            (key, event.bank[:50], event.amount, event.direction, event.content[:500], event.balance,
             event.txn_time, received, event.raw[:2000], status,
             payment["id"] if payment else None, 1 if status in RESOLVED_OK else 0),
        )
        db.log("reader", f"txn_{status}", payment_id=payment["id"] if payment else None,
               txn_id=cur.lastrowid, detail=f"amount={event.amount}")
        if payment:
            payment = db.one("SELECT * FROM payments WHERE id = ?", (payment["id"],))
        return ProcessResult(status=status, txn_id=cur.lastrowid, payment=payment,
                             newly_paid=newly_paid, excess=excess)


def _get_txn(db: Database, txn_id: int) -> dict[str, Any]:
    txn = db.one("SELECT * FROM bank_txns WHERE id = ?", (txn_id,))
    if not txn:
        raise ServiceError("Không tìm thấy giao dịch", 404)
    return txn


def assign_txn(db: Database, txn_id: int, ref_code: str, actor: str) -> ProcessResult:
    """Nhân viên ghép tay một giao dịch chưa khớp vào đơn đang chờ."""
    with db.lock:
        txn = _get_txn(db, txn_id)
        if txn["direction"] != "in" or txn["resolved"] or txn["match_status"] not in (UNMATCHED, INACTIVE_PAYMENT):
            raise ServiceError("Chỉ ghép được giao dịch tiền vào chưa khớp và chưa xử lý", 409)
        payment = get_payment_by_ref(db, ref_code)
        if payment["status"] not in ACTIVE_STATUSES:
            raise ServiceError("Đơn này không ở trạng thái chờ thanh toán", 409)
        result, newly_paid, excess = _apply_money(db, payment, txn["amount"], txn["received_at"])
        db.execute(
            "UPDATE bank_txns SET match_status = ?, payment_id = ?, resolved = ?, resolution = 'assigned'"
            " WHERE id = ?",
            (MANUAL if result in (MATCHED, LATE) else result, payment["id"],
             1 if result in (MATCHED, LATE) else 0, txn_id),
        )
        db.log(actor, "assign", payment_id=payment["id"], txn_id=txn_id, detail=result)
        payment = db.one("SELECT * FROM payments WHERE id = ?", (payment["id"],))
        return ProcessResult(status=result, txn_id=txn_id, payment=payment,
                             newly_paid=newly_paid, excess=excess)


RESOLUTIONS = {"refunded": "Đã hoàn tiền", "kept_credit": "Giữ làm số dư cho khách",
               "confirmed": "Đã kiểm tra, hợp lệ", "other": "Khác"}


def resolve_txn(db: Database, txn_id: int, resolution: str, note: str, actor: str) -> dict[str, Any]:
    if resolution not in RESOLUTIONS:
        raise ServiceError("Cách xử lý không hợp lệ")
    with db.lock:
        txn = _get_txn(db, txn_id)
        if txn["resolved"]:
            raise ServiceError("Giao dịch đã được xử lý", 409)
        db.execute("UPDATE bank_txns SET resolved = 1, resolution = ?, note = ? WHERE id = ?",
                   (resolution, note[:500], txn_id))
        db.log(actor, "resolve", payment_id=txn["payment_id"], txn_id=txn_id,
               detail=f"{resolution}: {note[:200]}")
        return _get_txn(db, txn_id)


# ---------------------------------------------------------------- báo về ERP

def erp_payload(db: Database, payment: dict[str, Any]) -> dict[str, Any]:
    txns = db.all("SELECT id, bank, amount, content, txn_time, received_at FROM bank_txns"
                  " WHERE payment_id = ? AND direction = 'in' ORDER BY id", (payment["id"],))
    return {
        "event": "payment.paid",
        "order_code": payment["order_code"],
        "ref_code": payment["ref_code"],
        "amount": payment["amount"],
        "paid_amount": payment["paid_amount"],
        "paid_at": payment["paid_at"],
        "transactions": txns,
    }


def sign(body: bytes, secret: str) -> str:
    return hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()


def notify_erp(db: Database, settings: Settings, payment_id: int) -> None:
    """Gửi sự kiện 'đã thanh toán' về ERP (nếu đã cấu hình ERP_CALLBACK_URL)."""
    if not settings.erp_callback_url:
        return
    import httpx

    payment = db.one("SELECT * FROM payments WHERE id = ?", (payment_id,))
    if not payment or payment["status"] != "paid":
        return
    body = json.dumps(erp_payload(db, payment), ensure_ascii=False).encode()
    headers = {"Content-Type": "application/json"}
    if settings.erp_callback_secret:
        headers["X-Signature"] = sign(body, settings.erp_callback_secret)
    try:
        response = httpx.post(settings.erp_callback_url, content=body, headers=headers, timeout=10)
        response.raise_for_status()
        db.execute("UPDATE payments SET erp_notified = 1, erp_error = '' WHERE id = ?", (payment_id,))
        db.log("system", "erp_notified", payment_id=payment_id)
    except Exception as exc:  # ghi lỗi để nhân viên bấm gửi lại
        db.execute("UPDATE payments SET erp_error = ? WHERE id = ?", (str(exc)[:300], payment_id))
        db.log("system", "erp_error", payment_id=payment_id, detail=str(exc)[:300])
