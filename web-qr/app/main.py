"""Web QR — tạo mã thanh toán có thời hạn và tự xác nhận chuyển khoản."""
from __future__ import annotations

import io
import secrets
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlencode
from zoneinfo import ZoneInfo

import segno
from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field

from . import services, vietqr
from .config import Settings, load_settings
from .db import Database, now
from .services import ServiceError

TEMPLATES_DIR = Path(__file__).parent / "templates"
NO_STORE = {"Cache-Control": "no-store"}


class PaymentIn(BaseModel):
    order_code: str = Field(..., max_length=64)
    amount: int = Field(..., gt=0)
    # None = dùng mặc định; 0 = không hết hạn
    expires_in_minutes: int | None = None
    note: str = ""


class BankEventIn(BaseModel):
    amount: int = Field(..., gt=0)
    direction: str = "in"
    content: str = ""
    bank: str = ""
    txn_id: str = ""
    balance: int | None = None
    txn_time: int | None = None  # epoch giây
    raw: str = ""


class AssignIn(BaseModel):
    ref_code: str


class ResolveIn(BaseModel):
    resolution: str
    note: str = ""


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings()
    db = Database(settings.db_path)
    tz = ZoneInfo(settings.timezone)
    templates = Jinja2Templates(directory=str(TEMPLATES_DIR))

    def fmt_time(ts: int | None) -> str:
        return datetime.fromtimestamp(ts, tz).strftime("%H:%M %d/%m/%Y") if ts else ""

    def fmt_money(value: int | None) -> str:
        return f"{value or 0:,}".replace(",", ".") + "đ"

    templates.env.filters["time"] = fmt_time
    templates.env.filters["money"] = fmt_money

    app = FastAPI(title="Web QR", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.db = db
    app.state.settings = settings
    basic = HTTPBasic(auto_error=False)

    # ------------------------------------------------------------ xác thực
    def require_admin(
        credentials: HTTPBasicCredentials | None = Depends(basic),
        x_api_key: str | None = Header(default=None),
    ) -> str:
        if x_api_key and secrets.compare_digest(x_api_key, settings.admin_api_key):
            return "api"
        if credentials and secrets.compare_digest(credentials.username, settings.admin_user) \
                and secrets.compare_digest(credentials.password, settings.admin_password):
            return f"admin:{credentials.username}"
        raise HTTPException(401, "Cần đăng nhập", headers={"WWW-Authenticate": "Basic"})

    def require_reader(x_reader_key: str | None = Header(default=None)) -> None:
        if not x_reader_key or not secrets.compare_digest(x_reader_key, settings.reader_secret):
            raise HTTPException(401, "Sai khóa app đọc tin")

    @app.exception_handler(ServiceError)
    async def _service_error(_: Request, exc: ServiceError):
        return JSONResponse({"error": exc.message}, status_code=exc.status_code)

    # ------------------------------------------------------------ tiện ích
    def pay_url(payment: dict[str, Any]) -> str:
        return f"{settings.public_base_url}/tt/{payment['token']}"

    def payment_out(payment: dict[str, Any]) -> dict[str, Any]:
        base = settings.public_base_url
        return {
            "order_code": payment["order_code"],
            "ref_code": payment["ref_code"],
            "amount": payment["amount"],
            "paid_amount": payment["paid_amount"],
            "remaining": services.remaining_amount(payment),
            "state": services.effective_state(payment),
            "created_at": payment["created_at"],
            "expires_at": payment["expires_at"],
            "paid_at": payment["paid_at"],
            "erp_notified": bool(payment["erp_notified"]),
            "erp_error": payment["erp_error"],
            "pay_url": pay_url(payment),
            "qr_png_url": f"{base}/qr/{payment['token']}.png",
            "qr_svg_url": f"{base}/qr/{payment['token']}.svg",
            "print_url": f"{base}/print/{payment['token']}",
        }

    def payment_or_404(token: str) -> dict[str, Any]:
        payment = services.get_payment_by_token(db, token)
        if not payment:
            raise HTTPException(404, "Không tìm thấy mã thanh toán")
        return payment

    def deeplinks(payment: dict[str, Any]) -> list[dict[str, str]]:
        query = {
            "ba": f"{settings.account_number}@{settings.bank_bin}",
            "am": str(services.remaining_amount(payment)),
            "tn": payment["ref_code"],
        }
        return [{"label": label,
                 "url": f"{settings.deeplink_base}?{urlencode({'app': app_id, **query})}"}
                for app_id, label in settings.bank_apps]

    def after_process(result: services.ProcessResult, background: BackgroundTasks) -> None:
        if result.newly_paid and result.payment:
            background.add_task(services.notify_erp, db, settings, result.payment["id"])

    # ------------------------------------------------------------ trang công khai
    @app.get("/healthz")
    def healthz():
        return {"ok": True}

    @app.get("/")
    def root():
        return RedirectResponse("/admin")

    @app.get("/tt/{token}", response_class=HTMLResponse)
    def pay_page(request: Request, token: str):
        payment = payment_or_404(token)
        state = services.effective_state(payment)
        active = state in services.ACTIVE_STATUSES
        context = {
            "settings": settings,
            "payment": payment,
            "state": state,
            "remaining": services.remaining_amount(payment),
            "seconds_left": (payment["expires_at"] - now()) if payment["expires_at"] and active else None,
            # Chỉ đưa thông tin chuyển khoản ra trang khi mã còn hiệu lực.
            "bank": {
                "name": settings.bank_name,
                "account_number": settings.account_number,
                "account_name": settings.account_name,
            } if active else None,
            "deeplinks": deeplinks(payment) if active else [],
        }
        return templates.TemplateResponse(request, "pay.html", context, headers=NO_STORE)

    @app.get("/tt/{token}/status")
    def pay_status(token: str):
        payment = payment_or_404(token)
        state = services.effective_state(payment)
        return JSONResponse({
            "state": state,
            "amount": payment["amount"],
            "paid_amount": payment["paid_amount"],
            "remaining": services.remaining_amount(payment),
            "expires_at": payment["expires_at"],
            "seconds_left": max(payment["expires_at"] - now(), 0) if payment["expires_at"] else None,
        }, headers=NO_STORE)

    @app.get("/tt/{token}/vietqr.svg")
    def pay_vietqr(token: str):
        """Mã VietQR chuyển khoản — CHỈ trả về khi mã còn hiệu lực."""
        payment = payment_or_404(token)
        if services.effective_state(payment) not in services.ACTIVE_STATUSES:
            raise HTTPException(410, "Mã đã hết hiệu lực")
        payload = vietqr.build_payload(settings.bank_bin, settings.account_number,
                                       services.remaining_amount(payment), payment["ref_code"])
        buffer = io.BytesIO()
        segno.make(payload, error="m").save(buffer, kind="svg", scale=6, border=2)
        return Response(buffer.getvalue(), media_type="image/svg+xml", headers=NO_STORE)

    @app.get("/qr/{token}.{ext}")
    def link_qr(token: str, ext: str, scale: int = 8):
        """Mã QR để IN lên hóa đơn: chỉ chứa link về trang thanh toán, không chứa số tài khoản/số tiền."""
        payment = payment_or_404(token)
        if ext not in ("png", "svg"):
            raise HTTPException(404)
        buffer = io.BytesIO()
        segno.make(pay_url(payment), error="m").save(buffer, kind=ext, scale=max(2, min(scale, 20)), border=2)
        return Response(buffer.getvalue(), media_type="image/png" if ext == "png" else "image/svg+xml")

    @app.get("/print/{token}", response_class=HTMLResponse)
    def print_page(request: Request, token: str):
        payment = payment_or_404(token)
        return templates.TemplateResponse(request, "print.html", {"settings": settings, "payment": payment})

    # ------------------------------------------------------------ API quản trị / ERP
    @app.get("/admin", response_class=HTMLResponse)
    def admin_page(request: Request, _: str = Depends(require_admin)):
        return templates.TemplateResponse(request, "admin.html", {
            "settings": settings, "resolutions": services.RESOLUTIONS}, headers=NO_STORE)

    @app.post("/api/payments")
    def api_create_payment(body: PaymentIn, actor: str = Depends(require_admin)):
        payment, created = services.create_payment(
            db, settings, body.order_code, body.amount, body.expires_in_minutes, body.note, actor)
        return JSONResponse(payment_out(payment), status_code=201 if created else 200)

    @app.get("/api/payments")
    def api_list_payments(limit: int = 50, _: str = Depends(require_admin)):
        rows = db.all("SELECT * FROM payments ORDER BY id DESC LIMIT ?", (max(1, min(limit, 500)),))
        return [payment_out(p) for p in rows]

    @app.get("/api/payments/{ref_code}")
    def api_get_payment(ref_code: str, _: str = Depends(require_admin)):
        return payment_out(services.get_payment_by_ref(db, ref_code))

    @app.post("/api/payments/{ref_code}/cancel")
    def api_cancel_payment(ref_code: str, actor: str = Depends(require_admin)):
        return payment_out(services.cancel_payment(db, ref_code, actor))

    @app.post("/api/payments/{ref_code}/notify-erp")
    def api_notify_erp(ref_code: str, _: str = Depends(require_admin)):
        payment = services.get_payment_by_ref(db, ref_code)
        if payment["status"] != "paid":
            raise ServiceError("Đơn chưa thanh toán đủ", 409)
        if not settings.erp_callback_url:
            raise ServiceError("Chưa cấu hình ERP_CALLBACK_URL", 409)
        services.notify_erp(db, settings, payment["id"])
        return payment_out(services.get_payment_by_ref(db, ref_code))

    @app.get("/api/transactions")
    def api_transactions(view: str = "review", limit: int = 100, _: str = Depends(require_admin)):
        where = "WHERE resolved = 0" if view == "review" else ""
        rows = db.all(
            f"SELECT t.*, p.ref_code, p.order_code FROM bank_txns t LEFT JOIN payments p"
            f" ON p.id = t.payment_id {where} ORDER BY t.id DESC LIMIT ?",
            (max(1, min(limit, 500)),))
        for row in rows:
            row.pop("raw", None)
        return rows

    @app.post("/api/transactions/{txn_id}/assign")
    def api_assign(txn_id: int, body: AssignIn, background: BackgroundTasks,
                   actor: str = Depends(require_admin)):
        result = services.assign_txn(db, txn_id, body.ref_code, actor)
        after_process(result, background)
        return {"status": result.status, "payment": payment_out(result.payment)}

    @app.post("/api/transactions/{txn_id}/resolve")
    def api_resolve(txn_id: int, body: ResolveIn, actor: str = Depends(require_admin)):
        return services.resolve_txn(db, txn_id, body.resolution, body.note, actor)

    # ------------------------------------------------------------ chế độ thử nghiệm
    @app.post("/api/demo/bank-event")
    def api_demo_bank_event(body: BankEventIn, background: BackgroundTasks,
                            _: str = Depends(require_admin)):
        """Giả lập app đọc tin gửi tiền về — chỉ khi DEMO_MODE bật."""
        if not settings.demo_mode:
            raise HTTPException(404)
        data = body.model_dump()
        data["bank"] = data["bank"] or "THU-NGHIEM"
        data["txn_id"] = data["txn_id"] or secrets.token_hex(6)
        result = services.process_bank_event(db, settings, services.BankEvent(**data))
        after_process(result, background)
        return {"status": result.status, "ref_code": result.payment["ref_code"] if result.payment else None,
                "excess": result.excess}

    # ------------------------------------------------------------ app đọc tin ngân hàng
    @app.post("/api/bank-events", dependencies=[Depends(require_reader)])
    def api_bank_event(body: BankEventIn, background: BackgroundTasks):
        result = services.process_bank_event(db, settings, services.BankEvent(**body.model_dump()))
        after_process(result, background)
        return {
            "status": result.status,
            "txn_id": result.txn_id,
            "ref_code": result.payment["ref_code"] if result.payment else None,
            "excess": result.excess,
        }

    return app
