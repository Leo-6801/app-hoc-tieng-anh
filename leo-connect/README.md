# Leo Connect

Điều khiển **điện thoại Android** từ **máy tính bảng Android** khi hai máy **cùng một mạng Wi-Fi**. Màn hình điện thoại hiện trong một **cửa sổ nổi** trên máy tính bảng. Không cần Tailscale hay máy chủ nào.

## Tính năng
- **Tự tìm điện thoại** trong mạng Wi-Fi, ghép đôi bằng **mã PIN 6 số**
- **Wi-Fi Direct 5 GHz:** sau khi ghép, hai máy tự mở đường nối thẳng với nhau ở 5 GHz (không qua router) và chuyển luồng hình sang đường đó. Lần sau máy tính bảng vào thẳng đường này, kể cả khi không có Wi-Fi chung. Cần quyền *Thiết bị ở gần* trên cả hai máy; nếu máy không mở được 5 GHz thì dùng Wi-Fi thường
- Đường truyền **mã hóa trong app** (trao đổi khóa ECDH + AES-256-GCM), chỉ nhận kết nối từ mạng nội bộ
- Hình **độ phân giải gốc, 60 khung hình/giây**, tự hạ chất lượng khi Wi-Fi yếu; dùng **H.265** khi hai máy hỗ trợ
- Cửa sổ nổi không khung, không hàng nút:
  - tay nắm **•••** phía trên (nằm ngoài màn hình): **kéo** để di chuyển, **nhấn giữ** để mở menu *Thu nhỏ thành bong bóng / Bàn phím ảo / Tắt cửa sổ*
  - **chụm/mở 2 ngón tay** trên màn hình để thu phóng
- **Chạm, vuốt, nhấn giữ** trực tiếp như cầm điện thoại
- Điều hướng như máy thật: **vuốt từ mép trái/phải vào** = Quay lại, **vuốt từ đáy lên** = Màn hình chính, **vuốt lên rồi giữ** = Đa nhiệm
- **Gõ bằng bàn phím rời**: chạm vào cửa sổ rồi gõ, luôn là tiếng Việt kiểu **Telex**. **Enter** = bấm nút Gửi của app, **Shift+Enter** = xuống dòng. Chạm ra ngoài cửa sổ để gõ ở app khác
- App bị văng thì lần mở sau **hiện thông tin lỗi** trên màn hình chính
- **Điều khiển qua Bluetooth (thử nghiệm):** máy tính bảng đóng vai màn hình cảm ứng + bàn phím Bluetooth (HID) của điện thoại, nên điện thoại không cần bật Trợ năng. Bật trong màn hình chính của máy tính bảng. Chỉ chạm 1 ngón; gõ tiếng Việt do bàn phím của điện thoại xử lý

## Cài đặt
1. Cho hai máy bắt **cùng một mạng Wi-Fi** (hoặc máy này bắt điểm phát sóng của máy kia).
2. Tải file **APK** mới nhất ở mục **Releases** của repo và cài trên **cả 2 máy**. Hai máy phải cùng phiên bản.

### Trên điện thoại
1. Mở Leo Connect, chọn **📱 Điện thoại**.
2. Bật **Trợ năng**: bấm *Mở cài đặt* → chọn *Leo Connect* → bật.
   - Nếu bị chặn ("cài đặt bị hạn chế"): *Cài đặt → Ứng dụng → Leo Connect → ⋮ → Cho phép cài đặt hạn chế*, rồi bật lại.
3. Bấm **Cho phép** ở mục *Không tối ưu pin*.
4. Bấm **Bật chế độ sẵn sàng**, chọn **Toàn bộ màn hình** → **Bắt đầu**.
5. Xem **Mã PIN** hiện trên màn hình.

### Trên máy tính bảng
1. Mở Leo Connect, chọn **💻 Máy tính bảng**.
2. Cho phép **hiển thị trên ứng dụng khác**.
3. Chọn điện thoại trong danh sách tự tìm thấy, nhập **mã PIN**, bấm **Kết nối**.
   - Không thấy điện thoại: kiểm tra hai máy cùng Wi-Fi và Wi-Fi không bật *cách ly thiết bị* (mạng khách). Có thể nhập tay địa chỉ Wi-Fi hiện trên điện thoại.

## Giới hạn hiện tại
- Chỉ dùng được khi hai máy **cùng mạng Wi-Fi**. Không điều khiển từ xa qua Internet.
- Mã PIN 6 số chống người nghe lén trong Wi-Fi, nhưng không đủ mạnh trước kẻ cố tình đứng giữa hai máy. Chỉ nên dùng ở Wi-Fi tin cậy.
- Sau mỗi lần **khởi động lại điện thoại** phải mở app và bấm *Bật chế độ sẵn sàng* lại.
- **App chặn chụp màn hình** (ngân hàng, một số app hẹn hò, ô mật khẩu) hiện **màu đen**.
- Gõ phím không vào được **ô mật khẩu** ở một số app.
- Chưa có: kéo thả file, clipboard, âm thanh, chuyển tiếp thông báo.

## Kỹ thuật
- Điện thoại: `MediaProjection` → `VirtualDisplay` → `MediaCodec` (H.265/H.264, trễ thấp) → TCP cổng **47800**. Tìm thiết bị bằng UDP phát rộng cổng **47801**. Chạm, phím và gõ chữ thực hiện bằng `AccessibilityService`.
- Máy tính bảng: giải mã `MediaCodec` lên `SurfaceView` trong cửa sổ `TYPE_APPLICATION_OVERLAY`.
- Bắt tay: ECDH P-256, hai máy chứng minh biết PIN bằng HMAC-SHA256 trên toàn bộ nội dung bắt tay; sau đó mọi gói mã hóa AES-256-GCM, mỗi chiều một khóa.
- Build: GitHub Actions (`.github/workflows/leo-connect.yml`) chạy kiểm thử (Telex, mã hóa) rồi tạo APK và bản phát hành.
- Khóa ký `app/leo-connect.keystore` dùng chung cho mọi bản build để **cài đè** được. Chỉ dùng cá nhân.
