// Bridge between the renderer and WFG (wfg/, MIT). WFG resolves Vulkan through a global
// VkDispatch (renamed wfg_vkd at build time so it can't clash with LSFG's `vkd`); it is filled
// here from the renderer's own table, because adrenotools drivers live in their own linker
// namespace and share no symbols with the system loader.
#define vkd wfg_vkd
#include "VulkanRendererContext.h"
#include "wfg_bridge.h"
extern "C" {
#include "vkr_dis.h"
}
#include <android/log.h>

extern "C" { VkDispatch wfg_vkd; }

static bool fillDispatch(const VkTable& t, VkDevice dev) {
    wfg_vkd.GetDeviceProcAddr = t.GetDeviceProcAddr;
    wfg_vkd.GetPhysicalDeviceProperties = t.GetPhysicalDeviceProperties;
    wfg_vkd.GetPhysicalDeviceMemoryProperties = t.GetPhysicalDeviceMemoryProperties;
    wfg_vkd.GetPhysicalDeviceFormatProperties = t.GetPhysicalDeviceFormatProperties;
    bool ok = t.GetDeviceProcAddr && t.GetPhysicalDeviceProperties && t.GetPhysicalDeviceMemoryProperties
              && t.GetPhysicalDeviceFormatProperties;
#define D(fn) do { wfg_vkd.fn = (PFN_vk##fn)t.GetDeviceProcAddr(dev, "vk" #fn); \
        if (!wfg_vkd.fn) { __android_log_print(ANDROID_LOG_ERROR, "YurnFrameGen", "WFG: vk" #fn " missing"); ok = false; } } while (0)
    if (!t.GetDeviceProcAddr) return false;
    D(AllocateDescriptorSets); D(AllocateMemory); D(BindImageMemory); D(CmdBindDescriptorSets);
    D(CmdBindPipeline); D(CmdBlitImage); D(CmdDispatch); D(CmdPipelineBarrier); D(CmdPushConstants);
    D(CreateComputePipelines); D(CreateDescriptorPool); D(CreateDescriptorSetLayout); D(CreateImage);
    D(CreateImageView); D(CreatePipelineLayout); D(CreateSampler); D(CreateShaderModule);
    D(DestroyDescriptorPool); D(DestroyDescriptorSetLayout); D(DestroyImage); D(DestroyImageView);
    D(DestroyPipeline); D(DestroyPipelineLayout); D(DestroySampler); D(DestroyShaderModule);
    D(FreeMemory); D(GetImageMemoryRequirements); D(UpdateDescriptorSets);
#undef D
    return ok;
}

namespace wfgb {
static inline VkrDis* d(void* p) { return static_cast<VkrDis*>(p); }

void* create(const VkTable& table, VkDevice device, VkPhysicalDevice pd) {
    if (!fillDispatch(table, device)) return nullptr;
    return vkr_dis_create(device, pd);
}
void destroy(void* p) { if (p) vkr_dis_destroy(d(p)); }
void configure(void* p, uint32_t minSide, uint32_t targetFps, float refreshHz) {
    if (p) vkr_dis_configure(d(p), minSide, targetFps, refreshHz);
}
bool prepare(void* p, uint32_t w, uint32_t h, VkFormat fmt) {
    if (!p) return false;
    VkrDisContentRect content{0, 0, w, h};
    if (!vkr_dis_needs_rebuild(d(p), w, h, fmt, content)) return true;
    vkr_dis_forget_targets(d(p));
    return vkr_dis_prepare(d(p), w, h, fmt, content);
}
uint32_t plan(void* p, uint32_t capacity, uint64_t frames) { return p ? vkr_dis_plan(d(p), capacity, frames) : 0; }
void process(void* p, VkCommandBuffer cmd, VkImage src, uint32_t w, uint32_t h, uint32_t gens) {
    if (p) vkr_dis_process(d(p), cmd, src, w, h, gens);
}
void generateInto(void* p, VkCommandBuffer cmd, uint32_t g, VkImage target, VkImageView view,
                  uint32_t w, uint32_t h, VkImage base) {
    if (p) vkr_dis_generate_into(d(p), cmd, g, g, target, view, w, h, base);
}
void reset(void* p) { if (p) vkr_dis_reset(d(p)); }
}
