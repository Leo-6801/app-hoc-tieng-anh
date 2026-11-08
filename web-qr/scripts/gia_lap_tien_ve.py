"""Giả lập app đọc tin gửi một giao dịch tiền về (để thử khi chưa có app Android).

Ví dụ:
  python scripts/gia_lap_tien_ve.py --url http://localhost:8000 --key <READER_SECRET> \
      --amount 1500000 --content "MBVCB.123.PL7K3M9QX.CT tu NGUYEN VAN B"
"""
import argparse
import time
import uuid

import httpx

parser = argparse.ArgumentParser()
parser.add_argument("--url", default="http://localhost:8000")
parser.add_argument("--key", required=True, help="READER_SECRET")
parser.add_argument("--amount", type=int, required=True)
parser.add_argument("--content", required=True)
parser.add_argument("--bank", default="TEST")
parser.add_argument("--txn-id", default=None, help="Mã giao dịch (mặc định: ngẫu nhiên)")
args = parser.parse_args()

response = httpx.post(
    f"{args.url.rstrip('/')}/api/bank-events",
    headers={"X-Reader-Key": args.key},
    json={
        "amount": args.amount,
        "direction": "in",
        "content": args.content,
        "bank": args.bank,
        "txn_id": args.txn_id or uuid.uuid4().hex[:12],
        "txn_time": int(time.time()),
    },
    timeout=10,
)
print(response.status_code, response.json())
