#include <stdio.h>
// #include "GpioService.h"
#include "CanService.h"
// #include "CalculatorService.h"

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/binder_auto_utils.h>
#include <android/binder_libbinder.h>

using namespace aidl::hust::can;
using namespace std;

void canTest()
{
    shared_ptr<CanService> canService = ndk::SharedRefBase::make<CanService>();
    bool result = false;

    canService->canOpen("can0", 500000, &result);
    if (result)
    {
        printf("CAN interface opened successfully\n");

        canService->canWrite(0x768, {0x03, 0x22, 0x01, 0x03, 0x00, 0x00, 0x00, 0x00}, false, &result);
        if (result)
        {
            printf("CAN frame written successfully\n");
        }
        else
        {
            printf("Failed to write CAN frame\n");
        }

        // Buffer phải đủ 12 byte (4 byte canId + tối đa 8 byte payload),
        // giống buffer mà app Android truyền vào.
        vector<uint8_t> data(12);
        int32_t dlc = 0;
        canService->canRead(&data, &dlc);
        if (dlc != -1)
        {
            uint32_t canId = data[0] | (data[1] << 8) | (data[2] << 16) | ((uint32_t)data[3] << 24);
            printf("CAN frame read successfully, canId=0x%X, dlc=%d, payload: ", canId, dlc);
            for (int i = 0; i < dlc; i++) {
                printf("%02X ", data[4 + i]);
            }
            printf("\n");
        }
        else
        {
            printf("Failed to read CAN frame (timeout hoac loi)\n");
        }

        canService->canClose();
    }
    else
    {
        printf("Failed to open CAN interface\n");
    }
}

int main()
{
    // 4 binder thread thay vì 0: một call chậm (canRead đang chờ frame)
    // sẽ không khoá cả service — service list/dumpsys vẫn trả lời được.
    ABinderProcess_setThreadPoolMaxThreadCount(4);
    ABinderProcess_startThreadPool();

    shared_ptr<CanService> canService = ndk::SharedRefBase::make<CanService>();
    string instanceName = string() + CanService::descriptor + "/default";
    binder_status_t status = AServiceManager_addService(canService->asBinder().get(), instanceName.c_str());

    if (status != STATUS_OK) {
        printf("Failed to add can service: %d\n", status);
        return -1;
    } else
        printf("CAN service added successfully\n");

    // canTest();   // gọi test ngay khi service khởi động, để debug

    ABinderProcess_joinThreadPool();
    return 0;
}
