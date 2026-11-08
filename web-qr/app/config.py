"""Cấu hình đọc từ biến môi trường (hoặc file .env)."""
from __future__ import annotations

import os
import re
from dataclasses import dataclass, field

try:  # .env là tùy chọn
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:  # pragma: no cover
    pass


def _get(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def _int(name: str, default: int) -> int:
    value = _get(name)
    return int(value) if value else default


def _parse_bank_apps(raw: str) -> list[tuple[str, str]]:
    """'vcb:Vietcombank,mb:MB Bank' -> [('vcb', 'Vietcombank'), ('mb', 'MB Bank')]"""
    apps = []
    for item in raw.split(","):
        if ":" in item:
            app_id, label = item.split(":", 1)
            if app_id.strip() and label.strip():
                apps.append((app_id.strip(), label.strip()))
    return apps


@dataclass
class Settings:
    shop_name: str = "Cửa hàng"
    bank_bin: str = ""
    bank_name: str = ""
    account_number: str = ""
    account_name: str = ""
    public_base_url: str = "http://localhost:8000"
    admin_api_key: str = ""
    admin_user: str = "admin"
    admin_password: str = ""
    reader_secret: str = ""
    ref_prefix: str = "PL"
    # 0 = không hết hạn
    default_expire_minutes: int = 15
    db_path: str = "webqr.db"
    erp_callback_url: str = ""
    erp_callback_secret: str = ""
    bank_apps: list[tuple[str, str]] = field(default_factory=list)
    deeplink_base: str = "https://dl.vietqr.io/pay"
    vietqr_show_seconds: int = 90
    timezone: str = "Asia/Ho_Chi_Minh"
    # Chế độ thử nghiệm: hiện ô "Giả lập tiền về" trong trang quản trị. KHÔNG bật khi chạy thật.
    demo_mode: bool = False

    def validate(self) -> None:
        if not re.fullmatch(r"[A-Z]{1,4}", self.ref_prefix):
            raise ValueError("REF_PREFIX phải là 1–4 chữ cái IN HOA, ví dụ PL")
        if not re.fullmatch(r"\d{6}", self.bank_bin or ""):
            raise ValueError("BANK_BIN phải gồm 6 chữ số (mã BIN ngân hàng NAPAS)")
        if not re.fullmatch(r"[0-9A-Za-z]{4,19}", self.account_number or ""):
            raise ValueError("ACCOUNT_NUMBER không hợp lệ")
        for name in ("admin_api_key", "admin_password", "reader_secret"):
            if len(getattr(self, name)) < 12:
                raise ValueError(f"{name.upper()} phải dài ít nhất 12 ký tự")


def load_settings() -> Settings:
    settings = Settings(
        shop_name=_get("SHOP_NAME", "Cửa hàng"),
        bank_bin=_get("BANK_BIN"),
        bank_name=_get("BANK_NAME"),
        account_number=_get("ACCOUNT_NUMBER"),
        account_name=_get("ACCOUNT_NAME").upper(),
        # Trên Render, RENDER_EXTERNAL_URL được cấp sẵn (https://<tên>.onrender.com)
        public_base_url=(_get("PUBLIC_BASE_URL") or _get("RENDER_EXTERNAL_URL")
                         or "http://localhost:8000").rstrip("/"),
        admin_api_key=_get("ADMIN_API_KEY"),
        admin_user=_get("ADMIN_USER", "admin"),
        admin_password=_get("ADMIN_PASSWORD"),
        reader_secret=_get("READER_SECRET"),
        ref_prefix=_get("REF_PREFIX", "PL").upper(),
        default_expire_minutes=_int("DEFAULT_EXPIRE_MINUTES", 15),
        db_path=_get("DB_PATH", "webqr.db"),
        erp_callback_url=_get("ERP_CALLBACK_URL"),
        erp_callback_secret=_get("ERP_CALLBACK_SECRET"),
        bank_apps=_parse_bank_apps(_get("BANK_APPS")),
        deeplink_base=_get("DEEPLINK_BASE", "https://dl.vietqr.io/pay"),
        vietqr_show_seconds=_int("VIETQR_SHOW_SECONDS", 90),
        timezone=_get("TIMEZONE", "Asia/Ho_Chi_Minh"),
        demo_mode=_get("DEMO_MODE").lower() in ("1", "true", "yes"),
    )
    settings.validate()
    return settings
