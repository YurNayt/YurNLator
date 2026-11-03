#pragma once
// WFG (WinNative/Winlator Frame Generator, qwertypower / DEVAR Entertainment LLC, MIT): a Dense
// Inverse Search optical-flow interpolator, driven by VulkanRendererFrameGen.cpp beside win-fg
// and LSFG. Its C API and Vulkan dispatch stay inside VulkanRendererWfg.cpp.
#include <vulkan/vulkan.h>
#include <cstdint>

struct VkTable;

namespace wfgb {
void*    create(const VkTable& table, VkDevice device, VkPhysicalDevice physicalDevice);
void     destroy(void* dis);
/** minSide: flow resolution, pixels on the shorter side (180 fast .. 252 balance). targetFps 0 = max. */
void     configure(void* dis, uint32_t minSide, uint32_t targetFps, float refreshHz);
bool     prepare(void* dis, uint32_t width, uint32_t height, VkFormat format);
uint32_t plan(void* dis, uint32_t capacity, uint64_t sourceFrames);
void     process(void* dis, VkCommandBuffer cmd, VkImage source, uint32_t width, uint32_t height, uint32_t generations);
void     generateInto(void* dis, VkCommandBuffer cmd, uint32_t generation, VkImage target, VkImageView view,
                      uint32_t width, uint32_t height, VkImage base);
void     reset(void* dis);
}
