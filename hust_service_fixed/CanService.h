#pragma once

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/binder_auto_utils.h>
#include <android/binder_libbinder.h>
#include "BnCan.h"
#include <stdio.h>
#include <stdint.h>
#include <mutex>
#include <gpiod.h>

namespace aidl::hust::can
{
class CanService : public BnCan
{
public:
    ndk::ScopedAStatus canOpen(const std::string& in_ifName, int32_t in_bitrate, bool* _aidl_return) override;
    ndk::ScopedAStatus canClose() override;
    ndk::ScopedAStatus canWrite(int32_t in_canId, const std::vector<uint8_t>& in_data, bool in_extended, bool* _aidl_return) override;
    ndk::ScopedAStatus canRead(std::vector<uint8_t>* out_data, int32_t* _aidl_return) override;

private:
    int socket_fd = -1;
    // Bảo vệ socket_fd khi chạy nhiều binder thread: tránh canClose()/canOpen()
    // đóng fd trong lúc thread khác đang poll()/read()/write() trên fd đó.
    std::mutex mutex_;
};
}
