#include "CanService.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>
#include <linux/can.h>
#include <net/if.h>

namespace aidl::hust::can
{
    // Timeout chờ frame trong canRead(): hết timeout thì trả -1 thay vì block
    // vô hạn. Block vô hạn sẽ chiếm binder thread → treo service + service list.
    static constexpr int kReadTimeoutMs = 200;

    ndk::ScopedAStatus CanService::canOpen(const std::string& in_ifName, int32_t in_bitrate, bool* _aidl_return)
    {
        (void)in_bitrate; // bitrate đang cấu hình sẵn ở tầng `ip link`, chưa dùng ở đây

        std::lock_guard<std::mutex> lock(mutex_);
        *_aidl_return = false;

        if (socket_fd != -1)
        {
            close(socket_fd);
            socket_fd = -1;
        }

        socket_fd = socket(PF_CAN, SOCK_RAW, CAN_RAW);
        if (socket_fd < 0) {
            printf("Error while opening socket: %s\n", strerror(errno));
            socket_fd = -1;
            return ndk::ScopedAStatus::ok();
        }

        // Nới rộng RX buffer: broadcast 0x3C6 chạy liên tục, khi VIN trả về một
        // burst Consecutive Frame mà vòng đọc chưa kịp gom thì buffer mặc định dễ
        // tràn → rớt khung → đọc VIN chập chờn. 1MB chịu được hàng nghìn frame.
        int rcvbuf = 1024 * 1024;
        if (setsockopt(socket_fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf)) < 0) {
            printf("Warning: cannot set SO_RCVBUF: %s\n", strerror(errno));
        }

        struct ifreq ifr;
        std::memset(&ifr, 0, sizeof(ifr));
        std::strncpy(ifr.ifr_name, in_ifName.c_str(), sizeof(ifr.ifr_name) - 1);

        if (ioctl(socket_fd, SIOCGIFINDEX, &ifr) < 0)
        {
            printf("Error getting interface index: %s\n", strerror(errno));
            close(socket_fd);
            socket_fd = -1;
            return ndk::ScopedAStatus::ok();
        }

        struct sockaddr_can addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.can_family = AF_CAN;
        addr.can_ifindex = ifr.ifr_ifindex;

        if (bind(socket_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            printf("Error in socket bind: %s\n", strerror(errno));
            close(socket_fd);
            socket_fd = -1;
            return ndk::ScopedAStatus::ok();
        }

        *_aidl_return = true;
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus CanService::canClose()
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (socket_fd != -1)
        {
            close(socket_fd);
            socket_fd = -1;
        }
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus CanService::canWrite(int32_t in_canId, const std::vector<uint8_t>& in_data, bool in_extended, bool* _aidl_return)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        *_aidl_return = false;

        if (socket_fd == -1)
        {
            return ndk::ScopedAStatus::ok();
        }

        struct can_frame frame;
        std::memset(&frame, 0, sizeof(frame));
        frame.can_id = in_canId;
        if (in_extended) {
            frame.can_id |= CAN_EFF_FLAG;
        }
        frame.can_dlc = in_data.size() > 8 ? 8 : in_data.size();
        if (frame.can_dlc > 0) {
            std::memcpy(frame.data, in_data.data(), frame.can_dlc);
        }

        ssize_t nbytes = write(socket_fd, &frame, sizeof(struct can_frame));
        if (nbytes != sizeof(struct can_frame)) {
            printf("Error writing to socket: %s\n", strerror(errno));
            return ndk::ScopedAStatus::ok();
        }

        *_aidl_return = true;
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus CanService::canRead(std::vector<uint8_t>* out_data, int32_t* _aidl_return)
    {
        // QUAN TRỌNG: không resize out_data. Với AIDL `out byte[]`, Java client
        // yêu cầu mảng trả về đúng bằng độ dài client truyền vào (app truyền
        // 12 byte); lệch độ dài sẽ ném "bad array lengths" phía app.
        std::fill(out_data->begin(), out_data->end(), 0);
        *_aidl_return = -1;

        // KHÔNG giữ mutex_ trong lúc poll(): giữ khóa suốt 200ms sẽ chặn
        // canWrite() (cùng mutex) → gói TX (request VIN, Flow Control) bị
        // trễ/đói khóa khi vòng đọc phía app quay liên tục.
        int fd;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            fd = socket_fd;
        }
        if (fd == -1)
        {
            return ndk::ScopedAStatus::ok();
        }

        struct pollfd pfd;
        std::memset(&pfd, 0, sizeof(pfd));
        pfd.fd = fd;
        pfd.events = POLLIN;

        int pr = poll(&pfd, 1, kReadTimeoutMs);
        if (pr < 0) {
            printf("Error polling socket: %s\n", strerror(errno));
            return ndk::ScopedAStatus::ok();
        }
        if (pr == 0 || !(pfd.revents & POLLIN)) {
            // Hết timeout, chưa có frame — trả -1, client sẽ gọi lại.
            return ndk::ScopedAStatus::ok();
        }

        std::lock_guard<std::mutex> lock(mutex_);
        if (socket_fd != fd) {
            // Socket đã bị đóng/mở lại trong lúc poll — bỏ qua lượt này.
            return ndk::ScopedAStatus::ok();
        }

        struct can_frame frame;
        ssize_t nbytes = read(socket_fd, &frame, sizeof(struct can_frame));
        if (nbytes < (ssize_t)sizeof(struct can_frame)) {
            printf("Error reading from socket: %s\n", strerror(errno));
            return ndk::ScopedAStatus::ok();
        }

        // Layout khớp với cách app parse (CanConnector.kt):
        // byte 0-3 = canId (little-endian), byte 4.. = payload, return = dlc.
        if (out_data->size() < 4u + frame.can_dlc) {
            printf("Client buffer too small: %zu bytes\n", out_data->size());
            return ndk::ScopedAStatus::ok();
        }

        uint32_t canId = frame.can_id &
            ((frame.can_id & CAN_EFF_FLAG) ? CAN_EFF_MASK : CAN_SFF_MASK);

        (*out_data)[0] = canId & 0xFF;
        (*out_data)[1] = (canId >> 8) & 0xFF;
        (*out_data)[2] = (canId >> 16) & 0xFF;
        (*out_data)[3] = (canId >> 24) & 0xFF;
        std::memcpy(out_data->data() + 4, frame.data, frame.can_dlc);

        *_aidl_return = frame.can_dlc;
        return ndk::ScopedAStatus::ok();
    }
}
