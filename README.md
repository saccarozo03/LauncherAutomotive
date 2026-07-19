# CANtrolX — Automotive Launcher & CAN Dashboard cho AOSP

Ứng dụng Android Automotive (đóng vai trò **Home/Launcher**) hiển thị và điều khiển các chức năng trên xe thông qua **CAN bus**, giao tiếp với ECU **NXP S32K144** (firmware AUTOSAR). App chạy trên AOSP và nói chuyện với phần cứng qua một **native AIDL service** dùng SocketCAN.

## Kiến trúc tổng thể

```
┌─────────────────────────────┐
│   App CANtrolX (Kotlin)     │  IntroActivity → MainActivity (dashboard)
│   com.example.navis_test    │  DebugActivity (gửi/nhận CAN thủ công)
└──────────────┬──────────────┘
               │ Binder (AIDL: hust.can.ICan/default)
┌──────────────▼──────────────┐
│  CanService (C++ / NDK)     │  hust_service_fixed/ — đăng ký qua
│  AServiceManager            │  AServiceManager_addService()
└──────────────┬──────────────┘
               │ SocketCAN (PF_CAN, SOCK_RAW)
┌──────────────▼──────────────┐
│  can0 (500 kbit/s)          │
└──────────────┬──────────────┘
               │ CAN bus
┌──────────────▼──────────────┐
│  ECU S32K144 (AUTOSAR)      │  Dcm/CanTp/Com — UDS + broadcast trạng thái
└─────────────────────────────┘
```

## Cấu trúc thư mục

| Đường dẫn | Nội dung |
|---|---|
| `app/` | Ứng dụng Android (Kotlin, View-based UI) |
| `app/src/main/aidl/hust/can/ICan.aidl` | Interface AIDL: `canOpen / canClose / canWrite / canRead` |
| `app/src/main/java/.../CanConnector.kt` | Kết nối binder tới service, executor I/O riêng + 1 luồng đọc dùng chung cho toàn app |
| `app/src/main/java/.../MainActivity.kt` | Dashboard chính: trạng thái xe, điều khiển đèn, nhiên liệu, VIN, hàng đợi UDS |
| `app/src/main/java/.../IntroActivity.kt` | Màn intro phát `res/raw/introapp.mp4`, là activity `HOME`/`LAUNCHER` |
| `app/src/main/java/.../DebugActivity.kt` | Màn debug: gửi frame CAN tuỳ ý, xem log RX/TX |
| `hust_service_fixed/` | Native CAN service (C++) build trong AOSP: `main.cpp`, `CanService.cpp/.h` |

## Giao diện

![Giao diện dashboard CANtrolX](docs/app_ui.png)

*Dashboard chính: trạng thái cửa/điều hoà, mức nhiên liệu, điều khiển đèn, đọc VIN và CAN monitor realtime.*

## Tính năng

- **Launcher thay CarLauncher**: `IntroActivity` khai báo intent-filter `HOME` + `LAUNCHER`, phát video intro rồi vào dashboard.
- **Trạng thái xe realtime** (broadcast `0x3C6` từ ECU): đèn bật/tắt, chế độ đèn, khoá cửa, điều hoà.
- **Điều khiển đèn** qua frame `0x3A6` (bật/tắt, chế độ Normal/Blink).
- **Nhiên liệu qua UDS** (`ReadDataByIdentifier 0x22`): poll mức nhiên liệu (DID `F1 01`) và trạng thái (DID `F1 02`) mỗi 1 giây.
- **Ghi ngưỡng cảnh báo** qua UDS `WriteDataByIdentifier 0x2E` (DID `F1 03`), có đọc lại để xác nhận ECU đã lưu.
- **Đọc VIN** qua ISO-TP (ISO 15765-2) multi-frame: First Frame → Flow Control → Consecutive Frame, có kiểm tra sequence number và tự retry khi rớt khung.
- **Hàng đợi UDS nối tiếp**: mọi request `0x768` đi qua một queue, chỉ 1 request chờ phản hồi tại một thời điểm (đúng kỷ luật UDS), giữ cycle time tối thiểu 1s giữa hai bản tin.
- **Dark mode** lưu qua SharedPreferences, màn debug CAN, log RX/TX trực tiếp trên UI.

## Bản tin CAN

| ID | Chiều | Ý nghĩa |
|---|---|---|
| `0x3C6` | ECU → App | Broadcast trạng thái: byte0 = đèn, byte1 = chế độ đèn, byte2 = khoá cửa, byte3 = điều hoà |
| `0x3A6` | App → ECU | Điều khiển đèn: byte0 = bật(01)/tắt(02), byte1 = normal(01)/blink(02) |
| `0x768` | App → ECU | UDS request (tester → DCM) |
| `0x769` | ECU → App | UDS response (single frame + ISO-TP multi-frame cho VIN) |

Các DID dùng trong UDS: `F1 01` (mức nhiên liệu %), `F1 02` (trạng thái nhiên liệu), `F1 03` (ngưỡng cảnh báo), `F1 90` (VIN).

## Build & chạy

### 1. App Android

Yêu cầu: Android Studio, compileSdk 37, minSdk 24.

```bash
./gradlew assembleDebug
```

App kết nối service qua `ServiceManager.getService("hust.can.ICan/default")` (reflection) nên **phải chạy như system app** (ưu tiên ký platform key) trong image AOSP thì mới lấy được binder — cài qua `adb install` thông thường sẽ không thấy service.

### 2. Native CAN service (build trong cây AOSP)

Copy `hust_service_fixed/` vào cây AOSP kèm file AIDL `ICan.aidl`, khai báo module (`Android.bp`) và init rc để service tự chạy khi boot. Service đăng ký tên `hust.can.ICan/default`.

Trước khi app mở được CAN, interface phải được cấu hình sẵn:

```bash
ip link set can0 type can bitrate 500000
ip link set can0 up
```

(`canOpen()` hiện chưa set bitrate — bitrate cấu hình ở tầng `ip link`.)

### 3. Nhúng vào AOSP làm launcher

Mục tiêu triển khai: build app thành **privileged/system app ký platform**, khai báo làm `HOME` mặc định thay CarLauncher trong image Android Automotive. Xem hướng dẫn chi tiết ở `AOSP_NHUNG_APP_GUIDE.md` (nếu có trong nhánh của bạn).

## Ghi chú kỹ thuật

- `canRead()` phía service poll tối đa 200 ms rồi trả `-1` (không block vô hạn để không chiếm binder thread); phía app, luồng đọc nghỉ 20 ms khi không có frame.
- Buffer đọc là **12 byte cố định**: 4 byte CAN ID (little-endian) + tối đa 8 byte payload; giá trị trả về là DLC. Không đổi kích thước buffer — AIDL `out byte[]` yêu cầu hai phía cùng độ dài.
- Service nới `SO_RCVBUF` lên 1 MB để không rớt khung ISO-TP khi bus có broadcast `0x3C6` liên tục.
- Mọi thao tác binder phía app (open/write/close) chạy trên một executor đơn luồng riêng, không bao giờ chạm main thread.
