# Leo Connect

Điều khiển **điện thoại Android** từ **máy tính bảng Android** qua Internet, màn hình điện thoại hiện trong một **cửa sổ nổi** trên máy tính bảng. Kết nối đi qua **Tailscale** (mạng riêng, mã hóa), nên không cần máy chủ riêng.

## Tính năng (giai đoạn 1)
- Màn hình điện thoại hiện trong **cửa sổ nổi** đè lên các app của máy tính bảng:
  - kéo thanh tiêu đề để **di chuyển**, kéo **◢** hoặc **chụm/mở 2 ngón tay** trên màn hình để **thu phóng** (giữ đúng tỉ lệ)
  - **chạm 2 lần vào thanh tiêu đề** để phóng to tối đa / trở về cỡ cũ
  - nút **—** thu nhỏ thành **bong bóng 📱**, chạm bong bóng để mở lại
  - tự đổi khung khi điện thoại **xoay ngang/dọc**
- **Chạm, vuốt, nhấn giữ** trực tiếp trên hình điện thoại. Giữ ngón tay lâu hơn 0,2 giây thì điện thoại **kéo/cuộn theo ngón tay ngay lúc đang vuốt**
- Nút **chất lượng** trên thanh tiêu đề: **Mượt** (1024px, 2 Mbps) · **Cân bằng** (1280px, 3,5 Mbps, mặc định) · **Nét** (1600px, 6 Mbps)
- Hiện **độ trễ** (ms) trên thanh tiêu đề. Khi mạng nghẽn, app **bỏ bớt khung hình** để hình không bị trễ dồn
- Thanh dưới: **◁ Quay lại · ○ Màn hình chính · ▢ Đa nhiệm · 🔔 Thông báo · 🔒 Khóa · ⌨ Gõ phím**
- **⌨ Gõ phím**: gõ bằng **bàn phím rời** (hoặc bàn phím ảo) của máy tính bảng vào ô nhập đang chọn trên điện thoại, **gõ được tiếng Việt** qua bộ gõ của máy tính bảng, có Backspace/Enter. Chạm ra ngoài cửa sổ thì tự tắt để gõ ở app khác
- Ghép đôi bằng **mã PIN 6 số**. Sai 5 lần thì điện thoại khóa 1 phút
- Chỉ nhận kết nối từ **mạng Tailscale** (`100.64.0.0/10`)
- Tự **kết nối lại** khi rớt mạng

## Cài đặt
1. Cài **Tailscale** trên cả 2 máy, đăng nhập **cùng một tài khoản**, bật kết nối.
2. Tải file **APK** mới nhất ở mục **Releases** của repo (bản `leo-connect-v0.1.x`) và cài trên **cả 2 máy**. Nếu Android hỏi, cho phép "cài ứng dụng không rõ nguồn gốc".

### Trên điện thoại
1. Mở Leo Connect, chọn **📱 Điện thoại**.
2. Bật **Trợ năng**: bấm *Mở cài đặt* → chọn *Leo Connect* → bật.
   - Nếu bị chặn ("cài đặt bị hạn chế"): *Cài đặt → Ứng dụng → Leo Connect → ⋮ → Cho phép cài đặt hạn chế*, rồi bật lại.
3. Bấm **Cho phép** ở mục *Không tối ưu pin*.
4. Bấm **Bật chế độ sẵn sàng**, chọn **Toàn bộ màn hình** → **Bắt đầu**.
5. Ghi lại **Địa chỉ Tailscale** (`100.x.x.x`) và **Mã PIN**.

### Trên máy tính bảng
1. Mở Leo Connect, chọn **💻 Máy tính bảng**.
2. Cho phép **hiển thị trên ứng dụng khác**.
3. Nhập **địa chỉ Tailscale** và **mã PIN** của điện thoại, bấm **Kết nối**.

## Giới hạn hiện tại
- Sau mỗi lần **khởi động lại điện thoại** phải mở app và bấm *Bật chế độ sẵn sàng* lại (Android bắt buộc xin quyền chia sẻ màn hình).
- **App ngân hàng, màn hình nhập mật khẩu** có thể hiện **màu đen**: Android chặn chụp các màn hình này.
- Điện thoại **tắt màn hình** thì app cố đánh thức màn hình khi có kết nối. Nếu điện thoại có **mã khóa**, có thể phải mở khóa trên hình điện thoại.
- Gõ phím không vào được **ô mật khẩu** ở một số app (Android không cho đọc nội dung ô mật khẩu).
- Chưa có: kéo thả file, clipboard, âm thanh, chuyển tiếp thông báo (các giai đoạn sau).

## Kỹ thuật
- Điện thoại: `MediaProjection` → `VirtualDisplay` → `MediaCodec` (H.264 baseline, chế độ trễ thấp, 3 mức chất lượng) → TCP cổng **47800**. Lệnh chạm thực hiện bằng `AccessibilityService.dispatchGesture` (không đọc nội dung màn hình).
- Máy tính bảng: giải mã `MediaCodec` lên `SurfaceView` trong cửa sổ `TYPE_APPLICATION_OVERLAY`.
- Xác thực: HMAC-SHA256 với khóa suy ra từ PIN, hai chiều (cả hai máy chứng minh biết PIN). Đường truyền được Tailscale (WireGuard) mã hóa.
- Build: GitHub Actions (`.github/workflows/leo-connect.yml`) tạo APK và bản phát hành mỗi lần có thay đổi trong `leo-connect/`.
- Khóa ký `app/leo-connect.keystore` dùng chung cho mọi bản build để **cài đè** được. Chỉ dùng cá nhân, không đưa lên Google Play.
