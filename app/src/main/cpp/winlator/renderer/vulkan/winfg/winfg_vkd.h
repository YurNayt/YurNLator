#pragma once
// YurNLator: fills win-fg's dispatch tables through vkGetDeviceProcAddr of the renderer's device
// (the renderer's VkTable differs from Bannerlator's, so the Bannerlator bridge isn't used).
#include "vk_dispatch.hpp"
struct VkTable;
bool winfgVkdInit(const VkTable& table, VkDevice dev, winfg::DeviceDispatch& dd, winfg::InstanceDispatch& id);
