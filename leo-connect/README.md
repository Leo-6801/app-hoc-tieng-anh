# Leo Connect

Điều khiển **điện thoại Android** từ **máy tính bảng Android** qua Internet, màn hình điện thoại hiện trong một **cửa sổ nổi** trên máy tính bảng. Kết nối đi qua **Tailscale** (mạng riêng, mã hóa), nên không cần máy chủ riêng.

## Tính năng
- Màn hình điện thoại hiện trong **cửa sổ nổi** (không khung, không thanh tiêu đề) đè lên các app của máy tính bảng:
  - **nắm một trong 4 góc** (chấm mờ) và kéo để **di chuyển**; chạm 2 lần vào góc để phóng to tối đa / trở lại
  - **chụm/mở 2 ngón tay** để **thu phóng** (giữ đúng tỉ lệ)
  - nút **—** thu nhỏ thành **bong bóng 📱**, chạm bong bóng để mở lại; nút **✕** để đóng
  - tự đổi khung khi điện thoại **xoay ngang/dọc**
- **Chạm, vuốt, nhấn giữ** trực tiếp. Giữ ngón tay hơn 0,2 giây thì điện thoại kéo/cuộn theo ngón tay
- Thanh dưới: **◁ Quay lại · ○ Màn hình chính · ▢ Đa nhiệm · ⌨ Gõ phím · chất lượng hình (+ độ trễ ms) · — · ✕**
- **⌨ Gõ phím** bằng bàn phím rời, **luôn gõ tiếng Việt kiểu Telex**. **Enter** = bấm nút Gửi của app, **Shift+Enter** = xuống dòng
- Chất lượng **Mượt / Cân bằng / Nét**, tự hạ khi mạng yếu; dùng **H.265** khi hai máy hỗ trợ
- Ghép đôi bằng **mã PIN 6 số**, chỉ nhận kết nối từ **mạng Tailscale**, tự **kết nối lại** khi rớt mạng
- App bị văng thì lần mở sau **hiện thông tin lỗi** trên màn hình chính

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
