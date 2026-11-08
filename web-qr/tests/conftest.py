import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import db as db_module, main as main_module, services as services_module  # noqa: E402
from app.config import Settings  # noqa: E402

ADMIN_KEY = "admin-key-123456"
READER_KEY = "reader-key-123456"


class Clock:
    def __init__(self, start: int = 1_800_000_000):
        self.t = start

    def __call__(self) -> int:
        return self.t

    def advance(self, seconds: int) -> None:
        self.t += seconds


@pytest.fixture
def clock(monkeypatch):
    c = Clock()
    for module in (db_module, main_module, services_module):
        monkeypatch.setattr(module, "now", c)
    return c


@pytest.fixture
def settings(tmp_path):
    s = Settings(
        shop_name="Shop Test", bank_bin="970436", bank_name="Vietcombank",
        account_number="0123456789", account_name="NGUYEN VAN A",
        public_base_url="https://pay.example.vn", admin_api_key=ADMIN_KEY,
        admin_user="admin", admin_password="admin-pass-123456", reader_secret=READER_KEY,
        ref_prefix="PL", default_expire_minutes=15, db_path=str(tmp_path / "t.db"),
        bank_apps=[("vcb", "Vietcombank")],
    )
    s.validate()
    return s


@pytest.fixture
def client(settings, clock):
    return TestClient(main_module.create_app(settings))


@pytest.fixture
def admin():
    return {"X-API-Key": ADMIN_KEY}


@pytest.fixture
def reader():
    return {"X-Reader-Key": READER_KEY}
