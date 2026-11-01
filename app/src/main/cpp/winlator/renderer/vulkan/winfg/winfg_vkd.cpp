// See winfg_vkd.h. Adapted from Bannerlator's winfg_vkd.cpp (MIT, The412Banner).
#include "winfg_vkd.h"
#include "../VulkanRendererContext.h"
#include <android/log.h>

bool winfgVkdInit(const VkTable& table, VkDevice dev, winfg::DeviceDispatch& dd, winfg::InstanceDispatch& id) {
    if (!table.GetDeviceProcAddr || dev == VK_NULL_HANDLE) return false;
    bool ok = true;
#define LOAD(fn)                                                                          \
    do {                                                                                  \
        dd.fn = reinterpret_cast<PFN_vk##fn>(table.GetDeviceProcAddr(dev, "vk" #fn));     \
        if (!dd.fn) {                                                                     \
            __android_log_print(ANDROID_LOG_ERROR, "WinFgVkd", "vk" #fn " did not resolve"); \
            ok = false;                                                                   \
        }                                                                                 \
    } while (0)
    LOAD(AllocateDescriptorSets);
    LOAD(AllocateMemory);
    LOAD(BindBufferMemory);
    LOAD(BindImageMemory);
    LOAD(CmdBindDescriptorSets);
    LOAD(CmdBindPipeline);
    LOAD(CmdClearColorImage);
    LOAD(CmdCopyImage);
    LOAD(CmdDispatch);
    LOAD(CmdPipelineBarrier);
    LOAD(CreateBuffer);
    LOAD(CreateComputePipelines);
    LOAD(CreateDescriptorPool);
    LOAD(CreateDescriptorSetLayout);
    LOAD(CreateImage);
    LOAD(CreateImageView);
    LOAD(CreatePipelineLayout);
    LOAD(CreateSampler);
    LOAD(CreateShaderModule);
    LOAD(DestroyBuffer);
    LOAD(DestroyDescriptorPool);
    LOAD(DestroyDescriptorSetLayout);
    LOAD(DestroyImage);
    LOAD(DestroyImageView);
    LOAD(DestroyPipeline);
    LOAD(DestroyPipelineLayout);
    LOAD(DestroySampler);
    LOAD(DestroyShaderModule);
    LOAD(DeviceWaitIdle);
    LOAD(FreeMemory);
    LOAD(GetBufferMemoryRequirements);
    LOAD(GetImageMemoryRequirements);
    LOAD(MapMemory);
    LOAD(ResetDescriptorPool);
    LOAD(UnmapMemory);
    LOAD(UpdateDescriptorSets);
#undef LOAD
    id.GetPhysicalDeviceMemoryProperties = table.GetPhysicalDeviceMemoryProperties;
    if (!id.GetPhysicalDeviceMemoryProperties) ok = false;
    return ok;
}
