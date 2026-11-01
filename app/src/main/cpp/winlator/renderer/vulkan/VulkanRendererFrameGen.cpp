// YurNLator: frame generation in the host renderer, with win-fg (Bannerlator's clean-room
// engine, MIT - see winfg/LICENSE.win-fg and winfg/NOTICE_FIDELITYFX_OPTICALFLOW.md).
//
// Why here and not as a Vulkan layer in the game: generated frames presented by the game travel
// Wine -> X server -> this renderer, which keeps only the latest content of a window, so they were
// dropped before reaching the screen (the game counted 2x, the screen showed 1x). Here they are
// generated from what this renderer composites and presented directly.
//
// Flow when armed (one game frame N arriving):
//   1. the scene is rendered into fgComposite (not into the swapchain image),
//   2. win-fg takes it as "curr" (N-1 becomes "prev") and synthesises k = 1..3 frames in between,
//   3. generated frame 1 is blitted to the acquired swapchain image and presented,
//   4. the other generated frames and then the real frame are presented, spaced evenly over the
//      measured game frame interval.
// Frames without new game content (cursor moves) only blit the composite.

#include "VulkanRendererContext.h"
#include "winfg/winfg_engine.h"
#include <chrono>
#include <cmath>

#define FG_LOG(...) __android_log_print(ANDROID_LOG_INFO, "YurnFrameGen", __VA_ARGS__)
#define FG_ERR(...) __android_log_print(ANDROID_LOG_ERROR, "YurnFrameGen", __VA_ARGS__)

static inline winfg::Engine* fgEng(void* p) { return static_cast<winfg::Engine*>(p); }

static int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void VulkanRendererContext::setFrameGen(bool armed, int multiplier, int preset, float flowScale) {
    fgMultiplier.store(std::clamp(multiplier, 2, 4));
    fgPreset.store(std::clamp(preset, 0, 2));
    fgFlowScale.store(std::clamp(flowScale, 0.25f, 1.0f));
    fgConfigDirty.store(true);
    fgArmed.store(armed);
    needsRender.store(true); dirtyCV.notify_one();
}

bool VulkanRendererContext::fgMakeImage(FgImage& out, VkImageUsageFlags usage) {
    VkImageCreateInfo ii{}; ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.imageType = VK_IMAGE_TYPE_2D; ii.extent = {fgW, fgH, 1}; ii.mipLevels = 1; ii.arrayLayers = 1;
    ii.format = swapchainFmt; ii.tiling = VK_IMAGE_TILING_OPTIMAL; ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage = usage; ii.samples = VK_SAMPLE_COUNT_1_BIT; ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vk_.CreateImage(device, &ii, nullptr, &out.img) != VK_SUCCESS) return false;
    VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device, out.img, &req);
    VkMemoryAllocateInfo ai{}; ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size; ai.memoryTypeIndex = findMemType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk_.AllocateMemory(device, &ai, nullptr, &out.mem) != VK_SUCCESS) return false;
    vk_.BindImageMemory(device, out.img, out.mem, 0);
    VkImageViewCreateInfo vi{}; vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = out.img; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = swapchainFmt;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return vk_.CreateImageView(device, &vi, nullptr, &out.view) == VK_SUCCESS;
}

void VulkanRendererContext::fgDestroyTargets() {
    if (device == VK_NULL_HANDLE) return;
    if (fgComposite.img || fgFramebuffer) vk_.DeviceWaitIdle(device);
    if (fgFramebuffer) { vk_.DestroyFramebuffer(device, fgFramebuffer, nullptr); fgFramebuffer = VK_NULL_HANDLE; }
    auto kill = [&](FgImage& f) {
        if (f.view) vk_.DestroyImageView(device, f.view, nullptr);
        if (f.img) vk_.DestroyImage(device, f.img, nullptr);
        if (f.mem) vk_.FreeMemory(device, f.mem, nullptr);
        f = FgImage{};
    };
    kill(fgComposite);
    for (auto& g : fgGen) kill(g);
    fgW = fgH = 0;
    if (fgEngine) fgEng(fgEngine)->reset();
}

void VulkanRendererContext::fgShutdown() {
    fgDestroyTargets();
    if (device == VK_NULL_HANDLE) return;
    if (fgRenderPass) { vk_.DestroyRenderPass(device, fgRenderPass, nullptr); fgRenderPass = VK_NULL_HANDLE; }
    if (fgSyncReady) {
        for (int i = 0; i < 3; i++) {
            vk_.DestroySemaphore(device, fgAcq[i], nullptr);
            vk_.DestroySemaphore(device, fgDone[i], nullptr);
            vk_.DestroyFence(device, fgFences[i], nullptr);
        }
        vk_.FreeCommandBuffers(device, cmdPool, 3, fgCmds);
        fgSyncReady = false;
    }
    delete fgEng(fgEngine);
    fgEngine = nullptr;
}

bool VulkanRendererContext::fgEnsureSync() {
    if (fgSyncReady) return true;
    if (!fgCmdBlitImage && vk_.GetDeviceProcAddr)
        fgCmdBlitImage = (PFN_vkCmdBlitImage)vk_.GetDeviceProcAddr(device, "vkCmdBlitImage");
    if (!fgCmdBlitImage) return false;
    std::lock_guard<std::mutex> lk(renderMutex);
    VkCommandBufferAllocateInfo ai{}; ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool = cmdPool; ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount = 3;
    if (vk_.AllocateCommandBuffers(device, &ai, fgCmds) != VK_SUCCESS) return false;
    VkSemaphoreCreateInfo si{}; si.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkFenceCreateInfo fi{}; fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    for (int i = 0; i < 3; i++) {
        vk_.CreateSemaphore(device, &si, nullptr, &fgAcq[i]);
        vk_.CreateSemaphore(device, &si, nullptr, &fgDone[i]);
        vk_.CreateFence(device, &fi, nullptr, &fgFences[i]);
    }
    fgSyncReady = true;
    return true;
}

bool VulkanRendererContext::fgEnsureTargets() {
    if (fgTargetsFailed) return false;
    if (fgW == swapchainExt.width && fgH == swapchainExt.height && fgComposite.img && fgFramebuffer) return true;
    fgDestroyTargets();
    fgW = swapchainExt.width; fgH = swapchainExt.height;
    if (fgW == 0 || fgH == 0) return false;

    if (!fgRenderPass) {
        // Same attachment as the main pass (so every existing pipeline is compatible), but it
        // ends in GENERAL for win-fg and the blit to the swapchain.
        VkAttachmentDescription att{}; att.format = swapchainFmt; att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED; att.finalLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{}; sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1; sub.pColorAttachments = &ref;
        VkSubpassDependency dep{}; dep.srcSubpass = VK_SUBPASS_EXTERNAL; dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
        dep.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo ci{}; ci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        ci.attachmentCount = 1; ci.pAttachments = &att; ci.subpassCount = 1; ci.pSubpasses = &sub;
        ci.dependencyCount = 1; ci.pDependencies = &dep;
        if (vk_.CreateRenderPass(device, &ci, nullptr, &fgRenderPass) != VK_SUCCESS) { fgTargetsFailed = true; return false; }
    }

    const VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                                  | VK_IMAGE_USAGE_SAMPLED_BIT;
    if (!fgMakeImage(fgComposite, usage)) { FG_ERR("composite image failed"); fgTargetsFailed = true; fgDestroyTargets(); return false; }
    for (auto& g : fgGen) {
        if (!fgMakeImage(g, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)) {
            FG_ERR("generated-frame image failed (storage on format %d unsupported?)", (int)swapchainFmt);
            fgTargetsFailed = true; fgDestroyTargets(); return false;
        }
    }
    VkFramebufferCreateInfo fi{}; fi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fi.renderPass = fgRenderPass; fi.attachmentCount = 1; fi.pAttachments = &fgComposite.view;
    fi.width = fgW; fi.height = fgH; fi.layers = 1;
    if (vk_.CreateFramebuffer(device, &fi, nullptr, &fgFramebuffer) != VK_SUCCESS) { fgTargetsFailed = true; fgDestroyTargets(); return false; }

    // Generated images live in GENERAL (win-fg writes them as storage, the blit reads them).
    std::lock_guard<std::mutex> lk(renderMutex);
    VkCommandBuffer cb = beginOneTime();
    for (auto& g : fgGen)
        transition(cb, g.img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0,
                   VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT,
                   VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    endOneTime(cb);
    FG_LOG("targets %ux%u fmt=%d", fgW, fgH, (int)swapchainFmt);
    return true;
}

bool VulkanRendererContext::fgPrepareFrame() {
    fgActiveThisFrame = false; fgNewThisFrame = false; fgGensThisFrame = 0;
    if (!fgArmed.load(std::memory_order_relaxed)) {
        if (fgW) { fgDestroyTargets(); fgLastFrameNs = 0; }
        return false;
    }
    if (!swapchainCanBlit) return false;
    if (!fgEnsureSync() || !fgEnsureTargets()) return false;

    if (!fgEngine && !fgEngineTried) {
        fgEngineTried = true;
        auto* e = new winfg::Engine();
        if (e->init(vk_, physicalDevice, device, graphicsQueueFamilyIndex, graphicsQueue)) {
            fgEngine = e;
            FG_LOG("win-fg engine ready");
        } else {
            delete e;
            FG_ERR("win-fg engine init failed: frame generation unavailable on this device");
        }
    }
    winfg::Engine* eng = fgEng(fgEngine);

    // Everything below reuses the same images every frame: start from an idle GPU.
    vk_.DeviceWaitIdle(device);
    fgActiveThisFrame = true;

    uint64_t serial = contentSerial.load(std::memory_order_relaxed);
    fgNewThisFrame = serial != fgLastSerial;
    fgLastSerial = serial;
    if (!fgNewThisFrame || !eng) return true;

    int64_t now = nowNs();
    if (fgLastFrameNs) {
        double dt = std::clamp((double)(now - fgLastFrameNs), 4e6, 100e6);
        fgIntervalNs = fgIntervalNs * 0.8 + dt * 0.2;
    }
    fgLastFrameNs = now;

    if (fgConfigDirty.exchange(false))
        eng->configure((uint32_t)fgMultiplier.load(), 3, fgPreset.load(), fgFlowScale.load());
    if (!eng->prepare(fgW, fgH, swapchainFmt)) return true;
    fgGensThisFrame = eng->plan(3);
    return true;
}

void VulkanRendererContext::fgBlitToSwapchain(VkCommandBuffer cb, VkImage src, uint32_t imgIdx) {
    VkImage dst = swapchainImages[imgIdx];
    VkImageMemoryBarrier pre[2]{};
    for (auto& b : pre) {
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    }
    pre[0].image = src; pre[0].oldLayout = VK_IMAGE_LAYOUT_GENERAL; pre[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    pre[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    pre[0].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    pre[1].image = dst; pre[1].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; pre[1].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    pre[1].srcAccessMask = 0; pre[1].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, pre);

    VkImageBlit r{};
    r.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    r.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    r.srcOffsets[1] = {(int32_t)fgW, (int32_t)fgH, 1};
    r.dstOffsets[1] = {(int32_t)swapchainExt.width, (int32_t)swapchainExt.height, 1};
    fgCmdBlitImage(cb, src, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r, VK_FILTER_NEAREST);

    VkImageMemoryBarrier post{}; post.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    post.srcQueueFamilyIndex = post.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    post.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    post.image = dst; post.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; post.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    post.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; post.dstAccessMask = 0;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        0, 0, nullptr, 0, nullptr, 1, &post);
}

void VulkanRendererContext::fgRecordTail(VkCommandBuffer cb, uint32_t imgIdx) {
    winfg::Engine* eng = fgEng(fgEngine);
    if (eng && fgNewThisFrame && eng->valid()) {
        eng->process(cb, fgComposite.img, fgW, fgH, fgGensThisFrame);
        for (uint32_t k = 0; k < fgGensThisFrame; k++)
            eng->generateInto(cb, k, fgGensThisFrame, fgGen[k].img, fgGen[k].view, fgW, fgH);
    } else {
        fgGensThisFrame = 0;
    }
    fgBlitToSwapchain(cb, fgGensThisFrame > 0 ? fgGen[0].img : fgComposite.img, imgIdx);
}

void VulkanRendererContext::fgPresentRest() {
    if (!fgActiveThisFrame || fgGensThisFrame == 0) return;
    const uint32_t n = fgGensThisFrame;
    const int64_t t0 = nowNs();
    const double step = fgIntervalNs / (double)(n + 1);
    // Remaining outputs in display order: generated 2..n, then the real frame.
    for (uint32_t i = 1; i <= n; i++) {
        VkImage src = (i < n) ? fgGen[i].img : fgComposite.img;
        int slot = (int)(i - 1);
        std::this_thread::sleep_until(std::chrono::steady_clock::time_point(
            std::chrono::nanoseconds(t0 + (int64_t)(step * i))));
        if (!isRunning || surfaceDetached.load() || swapchain == VK_NULL_HANDLE) return;

        vk_.WaitForFences(device, 1, &fgFences[slot], VK_TRUE, UINT64_MAX);
        uint32_t idx;
        VkResult res = vk_.AcquireNextImageKHR(device, swapchain, UINT64_MAX, fgAcq[slot], VK_NULL_HANDLE, &idx);
        if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_ERROR_SURFACE_LOST_KHR) { fbResized.store(true); return; }
        if ((res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) || idx >= swapchainImages.size()) return;
        if (idx < imgInFlight.size() && imgInFlight[idx] != VK_NULL_HANDLE && imgInFlight[idx] != fgFences[slot])
            vk_.WaitForFences(device, 1, &imgInFlight[idx], VK_TRUE, UINT64_MAX);
        if (idx < imgInFlight.size()) imgInFlight[idx] = VK_NULL_HANDLE;

        VkCommandBuffer cb = fgCmds[slot];
        vk_.ResetCommandBuffer(cb, 0);
        VkCommandBufferBeginInfo bi{}; bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vk_.BeginCommandBuffer(cb, &bi);
        fgBlitToSwapchain(cb, src, idx);
        vk_.EndCommandBuffer(cb);

        VkPipelineStageFlags ws = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo si{}; si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.waitSemaphoreCount = 1; si.pWaitSemaphores = &fgAcq[slot]; si.pWaitDstStageMask = &ws;
        si.commandBufferCount = 1; si.pCommandBuffers = &cb;
        si.signalSemaphoreCount = 1; si.pSignalSemaphores = &fgDone[slot];
        vk_.ResetFences(device, 1, &fgFences[slot]);
        if (vk_.QueueSubmit(graphicsQueue, 1, &si, fgFences[slot]) != VK_SUCCESS) return;

        VkPresentInfoKHR pi{}; pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        pi.waitSemaphoreCount = 1; pi.pWaitSemaphores = &fgDone[slot];
        pi.swapchainCount = 1; pi.pSwapchains = &swapchain; pi.pImageIndices = &idx;
        res = vk_.QueuePresentKHR(graphicsQueue, &pi);
        if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_ERROR_SURFACE_LOST_KHR || res == VK_SUBOPTIMAL_KHR) { fbResized.store(true); return; }
    }
}
