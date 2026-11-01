#pragma once
#include <vulkan/vulkan.h>
#include <list>
#include <cstddef>
#include <vulkan/vulkan_android.h>

#include "view_transformation.hpp"
#include "xform.hpp"

struct VkTable {

    PFN_vkCreateInstance CreateInstance;

    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;

    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkCreateRenderPass CreateRenderPass;
    PFN_vkDestroyRenderPass DestroyRenderPass;
    PFN_vkCreateFramebuffer CreateFramebuffer;
    PFN_vkDestroyFramebuffer DestroyFramebuffer;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkMapMemory MapMemory;
    PFN_vkFlushMappedMemoryRanges FlushMappedMemoryRanges;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkFreeDescriptorSets FreeDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass;
    PFN_vkCmdEndRenderPass CmdEndRenderPass;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdDraw CmdDraw;
    PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdSetViewport CmdSetViewport;
    PFN_vkCmdSetScissor CmdSetScissor;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyImage CmdCopyImage;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateSemaphore CreateSemaphore;
    PFN_vkDestroySemaphore DestroySemaphore;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences;
    PFN_vkGetFenceStatus GetFenceStatus;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;
};

#include <android/log.h>
#include <string>
#define WLOG_TAG "Winlator_Renderer"
#define RLOG(...) if(verboseLog) __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,__VA_ARGS__)
#define RLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,WLOG_TAG,__VA_ARGS__)

#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <jni.h>
#include <vector>
#include <unordered_map>
#include <algorithm>
#include <utility>
#include <thread>
#include <atomic>
#include <mutex>
#include <shared_mutex>
#include <condition_variable>
#include <chrono>

static constexpr uint32_t MAX_FRAMES_IN_FLIGHT = 2;

struct WindowPushConstants      { float ndcX0, ndcY0, ndcX1, ndcY1; int useTexAlpha;
                                  float _pad[7];
                                  float cosR = 1.f, sinR = 0.f;
                                  float brightness = 0.f, contrast = 1.f, gamma = 1.f, colorPad = 0.f; };
struct WindowPushConstantsSGSR  { float ndcX0, ndcY0, ndcX1, ndcY1; int useTexAlpha;
                                  float invSrcW, invSrcH, srcW, srcH;
                                  int   effectId;
                                  float resW;
                                  float sharpness;
                                  float cosR = 1.f, sinR = 0.f;
                                  float brightness = 0.f, contrast = 1.f, gamma = 1.f, colorPad = 0.f; };
struct WindowPushConstantsStretch { float ndcX0, ndcY0, ndcX1, ndcY1; int useTexAlpha; float strength; float profile;
                                  float _pad[5];
                                  float cosR = 1.f, sinR = 0.f;
                                  float brightness = 0.f, contrast = 1.f, gamma = 1.f, colorPad = 0.f; };

struct WindowPushConstantsFSR1  { float ndcX0, ndcY0, ndcX1, ndcY1; int useTexAlpha;
                                  float srcW, srcH, outW, outH;
                                  int   effectId;
                                  float sharpness;
                                  float _pad0;
                                  float cosR = 1.f, sinR = 0.f;
                                  float brightness = 0.f, contrast = 1.f, gamma = 1.f, colorPad = 0.f; };

struct WindowPushConstantsPostFX  { float ndcX0, ndcY0, ndcX1, ndcY1;
                                    int   effectId;
                                    float sharpness;
                                    float resW, resH;
                                    float _pad[4];
                                    float cosR = 1.f, sinR = 0.f;
                                  float brightness = 0.f, contrast = 1.f, gamma = 1.f, colorPad = 0.f; };

static_assert(offsetof(WindowPushConstants,       cosR) == 48, "cosR offset must match window_vert.txt layout(offset=48)");
static_assert(offsetof(WindowPushConstantsSGSR,   cosR) == 48, "cosR offset must match window_vert.txt layout(offset=48)");
static_assert(offsetof(WindowPushConstantsStretch,cosR) == 48, "cosR offset must match window_vert.txt layout(offset=48)");
static_assert(offsetof(WindowPushConstantsFSR1,   cosR) == 48, "cosR offset must match window_vert.txt layout(offset=48)");
static_assert(sizeof(WindowPushConstantsFSR1) == sizeof(WindowPushConstantsSGSR), "FSR1 push constants must fit the shared pipeline layout range");
static_assert(offsetof(WindowPushConstantsPostFX, cosR) == 48, "cosR offset must match window_vert.txt layout(offset=48)");
// YurNLator screen effects: brightness / contrast / gamma at the same offset in every layout (shaders: offset 56).
static_assert(offsetof(WindowPushConstants, brightness) == 56 && offsetof(WindowPushConstantsSGSR, brightness) == 56 &&
              offsetof(WindowPushConstantsStretch, brightness) == 56 && offsetof(WindowPushConstantsFSR1, brightness) == 56 &&
              offsetof(WindowPushConstantsPostFX, brightness) == 56, "color adjust offset must be 56");
static_assert(sizeof(WindowPushConstants) == sizeof(WindowPushConstantsSGSR) && sizeof(WindowPushConstantsStretch) == sizeof(WindowPushConstantsSGSR) &&
              sizeof(WindowPushConstantsPostFX) == sizeof(WindowPushConstantsSGSR), "push constant layouts must share one range");

class VulkanRendererContext {
public:
    VulkanRendererContext(ANativeWindow* window, int cWidth, int cHeight,
                          void* adrenotoolsHandle = nullptr);
    ~VulkanRendererContext();

    void onSurfaceResized(int width, int height);
    void setTransform(float ox, float oy, float sx, float sy);
    void updatePointerPosition(short x, short y);
    void updateWindowContent(int64_t id, void* pixels, short w, short h, short stride, int x, int y);
    void updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short w, short h, int x, int y);
    void updateCursorImage(void* pixels, short w, short h, short stride, short hotX, short hotY);
    void setCursorVisible(bool visible);
    void setRenderList(const int64_t* ids, const int* xs, const int* ys, int count);
    void removeWindow(int64_t id);
    void clearBackbuffer() {}
    void beginBatch() {}
    void endBatch() {}
    std::atomic<bool> surfaceDetached{false};

    void detachSurface();
    bool reattachSurface(ANativeWindow* newWindow);

    bool verboseLog = true;
    void setVerboseLog(bool v) { verboseLog = v; }
    void dumpRendererInfo();

    std::string adrenoDriverPath;
    std::string adrenoDriverName;
    std::string adrenoNativeLibDir;
    void* vulkanHandle = nullptr;
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkTable vk_ = {};
    void loadCustomDriver();
    void loadInstanceDispatch();
    void loadDeviceDispatch();

    void setFilterMode(int mode);
    void setStretchMode(int mode);
    void setPostFXMode(int mode);
    void setSharpness(float s);
    void setColorAdjust(float brightness, float contrast, float gamma);
    void setSwapRB(bool enabled);
    void setPresentMode(VkPresentModeKHR mode);
    std::vector<int> getSupportedPresentModes() const;
    VkExtent2D getSwapchainExtent() const { return swapchainExt; }

    void setCustomScissor(int x, int y, int w, int h);
    void clearCustomScissor();
    inline void setTransformAndScissor(float ox, float oy, float sx, float sy,
                                       bool hasScissor,
                                       int scissorX, int scissorY, int scissorW, int scissorH) {
        setTransform(ox, oy, sx, sy);
        if (hasScissor) setCustomScissor(scissorX, scissorY, scissorW, scissorH);
        else            clearCustomScissor();
    }

private:
    struct WinTex {
        VkImage              img            = VK_NULL_HANDLE;
        VkDeviceMemory       mem            = VK_NULL_HANDLE;
        VkImageView          view           = VK_NULL_HANDLE;
        VkDescriptorSet      ds             = VK_NULL_HANDLE;
        VkBuffer             stg            = VK_NULL_HANDLE;
        VkDeviceMemory       stgMem         = VK_NULL_HANDLE;
        void*                mapped         = nullptr;
        VkDeviceSize         cap            = 0;
        int                  w              = 0;
        int                  h              = 0;
        bool                 dirty          = false;
        bool                 isAHB          = false;
        bool                 needsTransition = false;
        AHardwareBuffer*     ahb            = nullptr;
    };

    struct RenderEntry { int64_t id; int x, y; };
    struct DrawEntry {
        VkImage         img            = VK_NULL_HANDLE;
        VkDescriptorSet ds             = VK_NULL_HANDLE;
        VkBuffer        upload         = VK_NULL_HANDLE;
        int             x=0, y=0, w=0, h=0;
        bool            needsTransition = false;
        bool            isAHB          = false;
    };

    struct WinNode {
        int64_t id       = 0;
        int64_t parentId = 0;
        int64_t contentId = 0;
        int     x = 0, y = 0;
        int     width = 0, height = 0;
        bool    mapped   = false;
        bool    viewable = true;
        std::vector<int64_t> children;
    };

    std::unordered_map<int64_t, WinNode> windowTree;
    int64_t rootWindowId = 0;
    int     treeScreenW = 0, treeScreenH = 0;
    bool    treeDirty = true;

public:
    void initRootWindow(int64_t rootId, int64_t contentId, int width, int height);
    void createWindowNode(int64_t id, int64_t parentId, int64_t contentId, int x, int y, int width, int height);
    void destroyWindowNode(int64_t id);
    void mapWindowNode(int64_t id);
    void unmapWindowNode(int64_t id);
    void reparentWindowNode(int64_t id, int64_t newParentId);
    void updateWindowGeometryNode(int64_t id, int64_t contentId, int x, int y, int width, int height);
    void syncChildOrder(int64_t parentId, const int64_t* orderedChildIds, int count);
    void setWindowViewable(int64_t id, bool viewable);

private:
    void collectWindowNodes(int64_t id, int absX, int absY,
                             std::vector<RenderEntry>& out,
                             std::vector<std::pair<int,int>>& outSizes);
    void rebuildRenderListFromTree();
public:

    ANativeWindow* window;
    int surfaceWidth, surfaceHeight, containerWidth, containerHeight;
    void* adrenotoolsHandle = nullptr;
    int filterMode  = 0;
    int stretchMode = 0;
    int postFXMode  = 0;
    float stretchStrength = 0.40f;
    float stretchProfile  = 0.60f;
    float sharpness = 0.5f;
    float colorBrightness = 0.f, colorContrast = 1.f, colorGamma = 1.f;
    bool swapRB = false;
    float maxAnisotropy           = 1.0f;
    VkPhysicalDeviceMemoryProperties memProperties{};
    VkPresentModeKHR requestedPresentMode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t graphicsQueueFamilyIndex = 0;
    std::vector<VkPresentModeKHR> availablePresentModes;

    std::unordered_map<int64_t, WinTex>         texMap;

    std::unordered_map<AHardwareBuffer*, WinTex>              ahbImportCache;
    std::unordered_map<int64_t, std::vector<AHardwareBuffer*>> windowAhbs;

    std::vector<WinTex>    deleteQueue;
    std::vector<RenderEntry> renderList;

    std::vector<DrawEntry>             frameDraws;
    std::vector<VkImageMemoryBarrier>  frameAhbTransitions;
    std::vector<VkImageMemoryBarrier>  framePreUpload;
    std::vector<VkImageMemoryBarrier>  framePostUpload;

    std::atomic<int>  pointerX{0}, pointerY{0};
    float sceneOffsetX=0.f, sceneOffsetY=0.f, sceneScaleX=1.f, sceneScaleY=1.f;

    std::atomic<bool> cursorVisible{false};
    short  cursorHotX=0, cursorHotY=0, cursorTexW=0, cursorTexH=0;
    std::vector<uint32_t>  cursorPixels;
    std::atomic<bool> isCursorImageDirty{false};
    std::atomic<bool> cursorMoved{false};

    VkImage         cursorImg   = VK_NULL_HANDLE;
    VkDeviceMemory  cursorMem   = VK_NULL_HANDLE;
    VkImageView     cursorView  = VK_NULL_HANDLE;
    VkDescriptorSet  cursorDS   = VK_NULL_HANDLE;
    VkBuffer         cursorStg  = VK_NULL_HANDLE;
    VkDeviceMemory   cursorStgM = VK_NULL_HANDLE;
    void*            cursorStgP = nullptr;
    VkDeviceSize     cursorStgC = 0;
    VkDeviceSize     cursorUploadSize = 0;

    VkInstance       instance;
    VkSurfaceKHR     surface;
    VkPhysicalDevice physicalDevice;
    VkDevice         device;
    VkQueue          graphicsQueue;
    VkSwapchainKHR   swapchain   = VK_NULL_HANDLE;
    VkFormat         swapchainFmt;

    VkExtent2D       swapchainExt;

    VkSurfaceTransformFlagBitsKHR surfaceTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;

    std::vector<VkImage>       swapchainImages;
    std::vector<VkImageView>   swapchainViews;
    std::vector<VkFramebuffer> swapchainFBs;

    VkRenderPass          renderPass  = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsLayout    = VK_NULL_HANDLE;
    VkPipelineLayout      pipeLayout  = VK_NULL_HANDLE;

    VkPipeline            pipeline        = VK_NULL_HANDLE;
    VkPipeline            sgsrPipeline    = VK_NULL_HANDLE;
    VkPipeline            fsr1Pipeline    = VK_NULL_HANDLE;
    VkPipeline            lanczosPipeline = VK_NULL_HANDLE;
    VkPipeline            stretchPipeline = VK_NULL_HANDLE;
    VkPipeline            postfxPipeline  = VK_NULL_HANDLE;

    VkCommandPool                cmdPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> cmdBufs;

    std::vector<VkSemaphore> imgAvailSems;
    std::vector<VkSemaphore> renderDoneSems;
    std::vector<VkFence>     inFlightFences;
    std::vector<VkFence>     imgInFlight;
    uint32_t                 currentFrame = 0;

    VkSampler        sampler    = VK_NULL_HANDLE;
    VkDescriptorPool winTexPool = VK_NULL_HANDLE;

    std::atomic<bool> needsRender{false};
    std::thread       renderThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> fbResized{false};
    std::mutex        renderMutex;
    std::mutex        dirtyMutex;
    std::condition_variable dirtyCV;
    std::shared_mutex frameMutex;

    VkRect2D          customScissor    = {{0,0},{0,0}};
    bool              hasCustomScissor = false;

    void getPreRotationCosSin(float& cosR, float& sinR) const {
        switch (surfaceTransform) {
            case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:  cosR=0.f;  sinR=1.f;  break;
            case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR: cosR=-1.f; sinR=0.f;  break;
            case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR: cosR=0.f;  sinR=-1.f; break;
            default:                                       cosR=1.f;  sinR=0.f;  break;
        }
    }

    VkRect2D rotateRectLogicalToIdentity(VkRect2D r) const {
        const int Wi = (int)swapchainExt.width;
        const int Hi = (int)swapchainExt.height;
        int x=r.offset.x, y=r.offset.y, w=(int)r.extent.width, h=(int)r.extent.height;
        int ox,oy,ow,oh;
        switch (surfaceTransform) {
            case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:
                ox = Wi - h - y; oy = x; ow = h; oh = w; break;
            case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:
                ox = Wi - w - x; oy = Hi - h - y; ow = w; oh = h; break;
            case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:
                ox = y; oy = Hi - w - x; ow = h; oh = w; break;
            default:
                ox = x; oy = y; ow = w; oh = h; break;
        }
        return VkRect2D{{ox,oy},{(uint32_t)std::max(0,ow),(uint32_t)std::max(0,oh)}};
    }

    void createInstance();
    void createSurface();
    void pickPhysicalDevice();
    void createLogicalDevice();
    void createSwapchain();
    void createRenderPass();
    void createDSLayout();
    void createPipeline(bool blend, VkPipeline& out);
    void createSgsrPipeline();
    void createFsr1Pipeline();
    void createLanczosPipeline();
    void createStretchPipeline();
    void createPostFXPipeline();
    void createFramebuffers();
    void createCmdPool();
    void createSampler();
    void createWinTexPool();
    void createCursorPipeline();
    void createCursorDS();
    void createCmdBufs();
    void createSyncObjects();
    void cleanupSwapchain();

    bool  createWinTexResources(WinTex& wt, int w, int h);
    bool  importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb);
    void  cleanupAllAHBCache();
    void  flushDeleteQueue();
    void  destroyWinTex(WinTex& wt);
    void  ensureCursorTex(short w, short h);
    void  cleanupCursorTex();
    void  ensureCursorStaging(VkDeviceSize sz);

    void recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
        const std::vector<DrawEntry>& draws,
        std::vector<VkImageMemoryBarrier>& ahbTransitions,
        std::vector<VkImageMemoryBarrier>& preUpload,
        std::vector<VkImageMemoryBarrier>& postUpload,
        VkBuffer cursorUpload, bool hasCursorUpload,
        float ox, float oy, float sx, float sy, float cw, float ch,
        short ptrX, short ptrY, short curHotX, short curHotY,
        short curW, short curH, bool curVis,
        VkRect2D scissorRect);
    void renderLoop();
    void renderFrame();

    uint32_t        findMemType(uint32_t filter, VkMemoryPropertyFlags props);
    void            createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem);
    VkCommandBuffer beginOneTime();
    void            endOneTime(VkCommandBuffer cmd);
    void            transition(VkCommandBuffer cmd, VkImage img,
                               VkImageLayout oldL, VkImageLayout newL,
                               VkAccessFlags srcA, VkAccessFlags dstA,
                               VkPipelineStageFlags srcS, VkPipelineStageFlags dstS);
    VkShaderModule  makeShader(const uint32_t* code, size_t sz);

    // ===== YurNLator frame generation (win-fg, Bannerlator, MIT) =====
    // When armed, the scene is composited into fgComposite instead of the swapchain image; on
    // each new game frame win-fg synthesises 1-3 frames between the previous and this one, and
    // they are presented first, spaced over the measured frame interval, then the real frame.
    void setFrameGen(bool armed, int multiplier, int preset, float flowScale);
    void noteContentFrame() { contentSerial.fetch_add(1, std::memory_order_relaxed); }
    std::atomic<bool>     fgArmed{false};
    std::atomic<int>      fgMultiplier{2};
    std::atomic<int>      fgPreset{1};
    std::atomic<float>    fgFlowScale{0.6f};
    std::atomic<bool>     fgConfigDirty{true};
    std::atomic<uint64_t> contentSerial{0};
    uint64_t fgLastSerial = 0;
    struct FgImage { VkImage img = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE; VkImageView view = VK_NULL_HANDLE; };
    FgImage       fgComposite;
    FgImage       fgGen[3];
    VkRenderPass  fgRenderPass = VK_NULL_HANDLE;
    VkFramebuffer fgFramebuffer = VK_NULL_HANDLE;
    uint32_t      fgW = 0, fgH = 0;
    bool          fgTargetsFailed = false;
    void*         fgEngine = nullptr;          // winfg::Engine*, owned
    bool          fgEngineTried = false;
    bool          fgActiveThisFrame = false;   // recordCmdBuf renders into the composite
    bool          fgNewThisFrame = false;      // a new game frame: feed win-fg
    uint32_t      fgGensThisFrame = 0;
    VkCommandBuffer fgCmds[3] = {};
    VkSemaphore   fgAcq[3] = {}, fgDone[3] = {};
    VkFence       fgFences[3] = {};
    bool          fgSyncReady = false;
    double        fgIntervalNs = 16.6e6;
    int64_t       fgLastFrameNs = 0;
    PFN_vkCmdBlitImage fgCmdBlitImage = nullptr;
    bool          swapchainCanBlit = false;   // swapchain images accept TRANSFER_DST
    bool          fgStorageFeatures = false;  // shaderStorageImageExtendedFormats enabled
    std::atomic<uint64_t> presentedRealFrames{0};
    uint64_t      hudLastSerial = 0;
    std::atomic<uint64_t> presentedGeneratedFrames{0};
    bool fgPrepareFrame();                 // before recording: decide composite / generation
    void fgRecordTail(VkCommandBuffer cb, uint32_t imgIdx);  // after the render pass
    void fgPresentRest();                  // after the first present: schedule the rest
    uint32_t fgPendNext = 0, fgPendTotal = 0;
    int64_t  fgPendT0 = 0;
    double   fgPendStep = 0;
    bool     fgHasPending() const;
    int64_t  fgNextDueNs() const;
    void     fgServicePending(bool newGameFrame);
    void     fgPresentOne(uint32_t i);
    void fgDestroyTargets();
    void fgShutdown();
    bool fgEnsureTargets();
    bool fgEnsureSync();
    bool fgMakeImage(FgImage& out, VkImageUsageFlags usage);
    void fgBlitToSwapchain(VkCommandBuffer cb, VkImage src, uint32_t imgIdx);
};
