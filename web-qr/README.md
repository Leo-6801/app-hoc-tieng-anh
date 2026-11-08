# Web QR: thanh toán chuyển khoản có thời hạn, tự xác nhận

Web tạo **mã QR thanh toán cho từng đơn** (lấy số tiền từ ERP). Mã có **thời hạn**: **hết hạn thì mã in trên giấy, hay ảnh chụp lại tờ giấy, không thanh toán được nữa**. Tiền về được **tự xác nhận** nhờ app Android đọc tin báo của ngân hàng. Không dùng dịch vụ trung gian.

## Cơ chế

```
ERP ──(mã đơn, số tiền)──→ Web QR ──→ mã QR dạng LINK in trên hóa đơn
                                          │ khách quét bằng camera / Zalo
                                          ↓
                          Web QR kiểm tra: còn hạn → mở app ngân hàng (điền sẵn)
                                           hết hạn → ⛔ không cấp thông tin chuyển khoản
Ngân hàng → SMS/thông báo → app Android đọc tin → POST /api/bank-events
                                          ↓
                          Web QR khớp mã nhận diện → "Đã thanh toán" → báo về ERP
```

- **Mã in trên giấy chỉ chứa link** `/tt/<token>`, không chứa số tài khoản hay số tiền. Mỗi lần quét đều phải qua máy chủ kiểm tra hạn.
- **Mã nhận diện** (ví dụ `PLM56HVAV`) được đặt trong nội dung chuyển khoản: chỉ chữ IN HOA và số, có ký tự kiểm tra. Hệ thống tìm mã ở bất kỳ vị trí nào trong nội dung (ngân hàng hay thêm chữ trước hoặc sau).
- Trang thanh toán **chỉ hiện thông tin chuyển khoản khi mã còn hiệu lực**. Mã VietQR (cho trường hợp quét từ thiết bị khác) chỉ hiện khi khách bấm, và tự ẩn sau 90 giây.

### Quy tắc khớp tiền

| Tình huống | Kết quả |
|---|---|
| Đúng mã, đủ tiền, còn hạn | ✅ Tự xác nhận |
| Đúng mã, đủ tiền, **sau hạn** (khách mở app trước hạn) | ✅ Tự xác nhận, ghi "trả sau hạn" |
| Trả thiếu | Đơn "Trả một phần", trang thanh toán hiện số còn lại, giao dịch chờ duyệt |
| Trả thừa | Đơn "Đã thanh toán", ghi tiền thừa, chờ duyệt |
| Đơn đã trả mà tiền lại về (**chuyển 2 lần**) | Chờ duyệt: hoàn tiền hoặc giữ số dư |
| Mã thuộc đơn đã hủy hoặc đóng | Chờ duyệt |
| Không có mã | "Không khớp mã", nhân viên ghép tay |
| App đọc tin gửi lặp cùng giao dịch | Bỏ qua, không cộng 2 lần |

## Chạy thử

```bash
cd web-qr
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env      # điền thông tin thật
uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000
```

- Trang quản trị: `http://<máy-chủ>:8000/admin` (đăng nhập bằng `ADMIN_USER` / `ADMIN_PASSWORD`)
- Giả lập tiền về (khi chưa có app Android):
  ```bash
  python scripts/gia_lap_tien_ve.py --url http://localhost:8000 --key <READER_SECRET> \
      --amount 1500000 --content "MBVCB.123.PLxxxxxxx.CT tu NGUYEN VAN B"
  ```
- Chạy kiểm thử: `pip install pytest && pytest`

### Bản thử nghiệm trên Render (có link HTTPS công khai)

File `render.yaml` ở thư mục gốc repo đã cấu hình sẵn (gói free, `DEMO_MODE=1`).

1. Vào https://render.com, đăng ký bằng tài khoản GitHub.
2. **New → Blueprint**, chọn repo `app-hoc-tieng-anh`. Nếu Render hỏi nhánh, chọn `ccr-cc07d8bd-mypsqs`.
3. Điền các giá trị Render hỏi: `BANK_BIN`, `BANK_NAME`, `ACCOUNT_NUMBER`, `ACCOUNT_NAME`, `ADMIN_PASSWORD` (ít nhất 12 ký tự).
4. Chờ build xong, mở `https://<tên>.onrender.com/admin` và đăng nhập `admin` / mật khẩu vừa đặt.

Ở chế độ thử nghiệm, trang quản trị có ô **"Giả lập tiền về"** để thử khớp tiền mà không cần chuyển thật.
Gói free **ngủ khi không dùng** (lần mở đầu chờ 30–60 giây) và **mất dữ liệu khi khởi động lại**. Chỉ dùng để thử.

Khi chạy thật, đặt web sau **HTTPS** (Nginx/Caddy hoặc Cloudflare Tunnel), và đặt `PUBLIC_BASE_URL` bằng địa chỉ công khai.

## API

Xác thực: header `X-API-Key: <ADMIN_API_KEY>` (hoặc đăng nhập Basic của trang quản trị).

| Phương thức | Đường dẫn | Mô tả |
|---|---|---|
| POST | `/api/payments` | Tạo mã. Body: `{"order_code","amount","expires_in_minutes"?,"note"?}`. `expires_in_minutes`: bỏ trống thì dùng mặc định, `0` là không hết hạn. Gọi lại cho cùng đơn và cùng số tiền khi mã còn hạn thì **trả về đúng mã cũ**. |
| GET | `/api/payments` | Danh sách mã gần đây |
| GET | `/api/payments/{ref_code}` | Chi tiết một mã |
| POST | `/api/payments/{ref_code}/cancel` | Hủy mã |
| POST | `/api/payments/{ref_code}/notify-erp` | Gửi lại sự kiện "đã thanh toán" về ERP |
| GET | `/api/transactions?view=review\|all` | Giao dịch cần xử lý hoặc tất cả |
| POST | `/api/transactions/{id}/assign` | Ghép tay: `{"ref_code"}` |
| POST | `/api/transactions/{id}/resolve` | Đánh dấu đã xử lý: `{"resolution": "refunded\|kept_credit\|confirmed\|other", "note"}` |

Kết quả tạo mã có `pay_url`, `qr_png_url` / `qr_svg_url` (ảnh QR để **in lên hóa đơn**), và `print_url` (trang in mẫu).

Trang công khai: `/tt/{token}` (trang thanh toán), `/tt/{token}/status` (trạng thái JSON), `/qr/{token}.png|svg`, `/print/{token}`.

### App đọc tin ngân hàng → Web QR

`POST /api/bank-events` với header `X-Reader-Key: <READER_SECRET>`:

```json
{"amount": 1500000, "direction": "in", "content": "MBVCB.123.PLM56HVAV.CT tu ...",
 "bank": "VCB", "txn_id": "FT123456", "balance": 25000000, "txn_time": 1794112605, "raw": "<tin gốc>"}
```

`txn_id` (mã giao dịch ngân hàng) dùng để chống trùng. Nếu tin không có mã giao dịch, gửi kèm `balance` (số dư sau giao dịch) để phân biệt 2 lần chuyển giống hệt nhau.

### Web QR → ERP

Khi đơn thành "đã thanh toán" và đã cấu hình `ERP_CALLBACK_URL`, Web QR gửi POST JSON:

```json
{"event": "payment.paid", "order_code": "HD000123", "ref_code": "PLM56HVAV",
 "amount": 1500000, "paid_amount": 1500000, "paid_at": 1794112605, "transactions": [...]}
```

Header `X-Signature` = HMAC-SHA256(body, `ERP_CALLBACK_SECRET`) dạng hex. Gửi lỗi thì trang quản trị hiện nút **Gửi ERP** để gửi lại.

## Giới hạn đã biết

- Khách **tự gõ tay** số tài khoản và nội dung thì luôn chuyển được. Hệ thống phát hiện và đưa vào danh sách cần xử lý.
- Mở app ngân hàng **trước hạn** nhưng chuyển **sau hạn** thì tiền vẫn về, và được ghi nhận là "trả sau hạn".
- Nút mở app ngân hàng dùng dịch vụ chuyển hướng công khai `dl.vietqr.io` (chỉ để mở app, không xác nhận tiền). **Mã app trong `BANK_APPS` cần thử lại với từng ngân hàng.** Để trống `BANK_APPS` để tắt.
- **Chưa có:** app Android đọc tin (mới có giả lập), kết nối thật với Phi Long ERP (chờ thông tin API hoặc mẫu in).
