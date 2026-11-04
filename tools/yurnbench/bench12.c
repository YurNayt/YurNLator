/*
 * YurnBench, Direct3D 12 path (--dx12): the same scene and phases as the D3D11 path, through
 * D3D12 (VKD3D-Proton on Android). Included by bench.c; uses its window, timing, stats and HLSL.
 * Two frames in flight; GPU time per frame from D3D12 timestamp queries resolved to a readback
 * buffer and read once the frame's fence has passed.
 */
#include <initguid.h>   /* the D3D12 / DXGI 1.4 interface ids aren't in mingw's libuuid */
#include <d3d12.h>
#include <dxgi1_4.h>

#define FRAMES 2

static ID3D12Device *d12;
static ID3D12CommandQueue *queue;
static IDXGISwapChain3 *swap3;
static ID3D12DescriptorHeap *rtvHeap;
static UINT rtvStep;
static ID3D12Resource *backBuf[FRAMES];
static ID3D12CommandAllocator *alloc[FRAMES];
static ID3D12GraphicsCommandList *list;
static ID3D12Fence *fence;
static HANDLE fenceEvent;
static UINT64 fenceValue = 0, frameFence[FRAMES];
static ID3D12RootSignature *rootSig;
static ID3D12PipelineState *psoFull, *psoPart;
static ID3D12Resource *inst12Gpu, *inst12Mix[FRAMES];
static float *mixMapped[FRAMES];
static ID3D12QueryHeap *tsHeap;
static ID3D12Resource *tsReadback;
static UINT64 tsFreq = 0;
static int tsPending[FRAMES], tsReps[FRAMES];

static int fail12(const char *what, HRESULT hr) {
    snprintf(errorText, sizeof errorText, "%s 0x%08lx", what, (unsigned long)hr);
    return 0;
}

static ID3D12Resource *uploadBuffer(UINT64 size) {
    D3D12_HEAP_PROPERTIES hp = {0};
    hp.Type = D3D12_HEAP_TYPE_UPLOAD;
    D3D12_RESOURCE_DESC rd = {0};
    rd.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
    rd.Width = size; rd.Height = 1; rd.DepthOrArraySize = 1; rd.MipLevels = 1;
    rd.SampleDesc.Count = 1; rd.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    ID3D12Resource *r = NULL;
    ID3D12Device_CreateCommittedResource(d12, &hp, D3D12_HEAP_FLAG_NONE, &rd, D3D12_RESOURCE_STATE_GENERIC_READ, NULL,
                                         &IID_ID3D12Resource, (void **)&r);
    return r;
}

static ID3D12PipelineState *pso(ID3DBlob *vs, ID3DBlob *ps, int particles) {
    D3D12_GRAPHICS_PIPELINE_STATE_DESC d;
    memset(&d, 0, sizeof d);
    d.pRootSignature = rootSig;
    d.VS.pShaderBytecode = ID3D10Blob_GetBufferPointer(vs); d.VS.BytecodeLength = ID3D10Blob_GetBufferSize(vs);
    d.PS.pShaderBytecode = ID3D10Blob_GetBufferPointer(ps); d.PS.BytecodeLength = ID3D10Blob_GetBufferSize(ps);
    D3D12_RENDER_TARGET_BLEND_DESC *b = &d.BlendState.RenderTarget[0];
    b->BlendEnable = particles ? TRUE : FALSE;
    b->SrcBlend = D3D12_BLEND_SRC_ALPHA; b->DestBlend = D3D12_BLEND_INV_SRC_ALPHA; b->BlendOp = D3D12_BLEND_OP_ADD;
    b->SrcBlendAlpha = D3D12_BLEND_ONE; b->DestBlendAlpha = D3D12_BLEND_ZERO; b->BlendOpAlpha = D3D12_BLEND_OP_ADD;
    b->LogicOp = D3D12_LOGIC_OP_NOOP;
    b->RenderTargetWriteMask = D3D12_COLOR_WRITE_ENABLE_ALL;
    d.SampleMask = 0xffffffff;
    d.RasterizerState.FillMode = D3D12_FILL_MODE_SOLID;
    d.RasterizerState.CullMode = D3D12_CULL_MODE_NONE;
    d.RasterizerState.DepthClipEnable = TRUE;
    d.DepthStencilState.DepthEnable = FALSE;
    d.DepthStencilState.StencilEnable = FALSE;
    static D3D12_INPUT_ELEMENT_DESC ie = { "INST", 0, DXGI_FORMAT_R32G32B32A32_FLOAT, 0, 0,
                                           D3D12_INPUT_CLASSIFICATION_PER_INSTANCE_DATA, 1 };
    if (particles) { d.InputLayout.pInputElementDescs = &ie; d.InputLayout.NumElements = 1; }
    d.PrimitiveTopologyType = D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;
    d.NumRenderTargets = 1;
    d.RTVFormats[0] = DXGI_FORMAT_R8G8B8A8_UNORM;
    d.SampleDesc.Count = 1;
    ID3D12PipelineState *p = NULL;
    HRESULT hr = ID3D12Device_CreateGraphicsPipelineState(d12, &d, &IID_ID3D12PipelineState, (void **)&p);
    if (FAILED(hr)) fail12(particles ? "CreateGraphicsPipelineState(particles)" : "CreateGraphicsPipelineState(full)", hr);
    return p;
}

static int init12(HWND hwnd) {
    HRESULT hr = D3D12CreateDevice(NULL, D3D_FEATURE_LEVEL_11_0, &IID_ID3D12Device, (void **)&d12);
    if (FAILED(hr)) return fail12("D3D12CreateDevice", hr);

    D3D12_COMMAND_QUEUE_DESC qd = {0};
    qd.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    if (FAILED(hr = ID3D12Device_CreateCommandQueue(d12, &qd, &IID_ID3D12CommandQueue, (void **)&queue))) return fail12("CreateCommandQueue", hr);
    ID3D12CommandQueue_GetTimestampFrequency(queue, &tsFreq);

    IDXGIFactory4 *fac = NULL;
    if (FAILED(hr = CreateDXGIFactory2(0, &IID_IDXGIFactory4, (void **)&fac))) return fail12("CreateDXGIFactory2", hr);
    IDXGIAdapter1 *ad = NULL;
    if (SUCCEEDED(IDXGIFactory4_EnumAdapters1(fac, 0, &ad))) {
        DXGI_ADAPTER_DESC1 d;
        if (SUCCEEDED(IDXGIAdapter1_GetDesc1(ad, &d))) {
            int j = 0;
            for (int i = 0; d.Description[i] && j < (int)sizeof adapterName - 1; i++)
                if (d.Description[i] >= 32 && d.Description[i] < 127 && d.Description[i] != '"' && d.Description[i] != '\\')
                    adapterName[j++] = (char)d.Description[i];
            adapterName[j] = 0;
        }
        IDXGIAdapter1_Release(ad);
    }
    DXGI_SWAP_CHAIN_DESC1 sd = {0};
    sd.Width = W; sd.Height = H; sd.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    sd.SampleDesc.Count = 1; sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.BufferCount = FRAMES; sd.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    IDXGISwapChain1 *sc1 = NULL;
    if (FAILED(hr = IDXGIFactory4_CreateSwapChainForHwnd(fac, (IUnknown *)queue, hwnd, &sd, NULL, NULL, &sc1)))
        return fail12("CreateSwapChainForHwnd", hr);
    IDXGISwapChain1_QueryInterface(sc1, &IID_IDXGISwapChain3, (void **)&swap3);
    IDXGISwapChain1_Release(sc1);
    IDXGIFactory4_Release(fac);
    if (!swap3) return fail12("IDXGISwapChain3", E_NOINTERFACE);

    D3D12_DESCRIPTOR_HEAP_DESC hd = {0};
    hd.Type = D3D12_DESCRIPTOR_HEAP_TYPE_RTV; hd.NumDescriptors = FRAMES;
    if (FAILED(hr = ID3D12Device_CreateDescriptorHeap(d12, &hd, &IID_ID3D12DescriptorHeap, (void **)&rtvHeap))) return fail12("CreateDescriptorHeap", hr);
    rtvStep = ID3D12Device_GetDescriptorHandleIncrementSize(d12, D3D12_DESCRIPTOR_HEAP_TYPE_RTV);
    D3D12_CPU_DESCRIPTOR_HANDLE h;
    rtvHeap->lpVtbl->GetCPUDescriptorHandleForHeapStart(rtvHeap, &h);
    for (int i = 0; i < FRAMES; i++) {
        if (FAILED(hr = IDXGISwapChain3_GetBuffer(swap3, i, &IID_ID3D12Resource, (void **)&backBuf[i]))) return fail12("GetBuffer", hr);
        D3D12_CPU_DESCRIPTOR_HANDLE hi = { h.ptr + (SIZE_T)i * rtvStep };
        ID3D12Device_CreateRenderTargetView(d12, backBuf[i], NULL, hi);
        if (FAILED(hr = ID3D12Device_CreateCommandAllocator(d12, D3D12_COMMAND_LIST_TYPE_DIRECT, &IID_ID3D12CommandAllocator, (void **)&alloc[i])))
            return fail12("CreateCommandAllocator", hr);
    }
    if (FAILED(hr = ID3D12Device_CreateCommandList(d12, 0, D3D12_COMMAND_LIST_TYPE_DIRECT, alloc[0], NULL,
                                                    &IID_ID3D12GraphicsCommandList, (void **)&list))) return fail12("CreateCommandList", hr);
    ID3D12GraphicsCommandList_Close(list);
    if (FAILED(hr = ID3D12Device_CreateFence(d12, 0, D3D12_FENCE_FLAG_NONE, &IID_ID3D12Fence, (void **)&fence))) return fail12("CreateFence", hr);
    fenceEvent = CreateEventA(NULL, FALSE, FALSE, NULL);

    /* Root signature: 4 root constants at b0 (time, iterations), the input assembler for particles. */
    D3D12_ROOT_PARAMETER rp;
    memset(&rp, 0, sizeof rp);
    rp.ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
    rp.Constants.ShaderRegister = 0; rp.Constants.RegisterSpace = 0; rp.Constants.Num32BitValues = 4;
    rp.ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
    D3D12_ROOT_SIGNATURE_DESC rs = {0};
    rs.NumParameters = 1; rs.pParameters = &rp;
    rs.Flags = D3D12_ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT;
    ID3DBlob *sig = NULL, *err = NULL;
    if (FAILED(hr = D3D12SerializeRootSignature(&rs, D3D_ROOT_SIGNATURE_VERSION_1, &sig, &err))) return fail12("D3D12SerializeRootSignature", hr);
    if (FAILED(hr = ID3D12Device_CreateRootSignature(d12, 0, ID3D10Blob_GetBufferPointer(sig), ID3D10Blob_GetBufferSize(sig),
                                                      &IID_ID3D12RootSignature, (void **)&rootSig))) return fail12("CreateRootSignature", hr);
    ID3D10Blob_Release(sig);

    ID3DBlob *vsF = compile("vs_full", "vs_5_0"), *psH = compile("ps_heavy", "ps_5_0");
    ID3DBlob *vsP = compile("vs_part", "vs_5_0"), *psP = compile("ps_part", "ps_5_0");
    if (!vsF || !psH || !vsP || !psP) return 0;
    if (!(psoFull = pso(vsF, psH, 0)) || !(psoPart = pso(vsP, psP, 1))) return 0;

    /* Particles of the GPU phase: the same fixed set as the D3D11 path. */
    inst12Gpu = uploadBuffer(sizeof(float) * 4 * GPU_PARTICLES);
    if (!inst12Gpu) return fail12("upload buffer", E_OUTOFMEMORY);
    float *p = NULL;
    D3D12_RANGE none = {0, 0};
    ID3D12Resource_Map(inst12Gpu, 0, &none, (void **)&p);
    unsigned x = 12345;
    for (int i = 0; i < GPU_PARTICLES; i++) {
        x = x * 1664525u + 1013904223u; p[i * 4 + 0] = ((x >> 8) & 0xffff) / 32768.f - 1.f;
        x = x * 1664525u + 1013904223u; p[i * 4 + 1] = ((x >> 8) & 0xffff) / 32768.f - 1.f;
        p[i * 4 + 2] = 0.02f + (i % 9) * 0.008f;
        p[i * 4 + 3] = (i % 97) / 97.f;
    }
    ID3D12Resource_Unmap(inst12Gpu, 0, NULL);
    for (int i = 0; i < FRAMES; i++) {
        inst12Mix[i] = uploadBuffer(sizeof(float) * 4 * MIX_PARTICLES);
        if (!inst12Mix[i]) return fail12("upload buffer", E_OUTOFMEMORY);
        ID3D12Resource_Map(inst12Mix[i], 0, &none, (void **)&mixMapped[i]);
    }

    D3D12_QUERY_HEAP_DESC qh = {0};
    qh.Type = D3D12_QUERY_HEAP_TYPE_TIMESTAMP; qh.Count = FRAMES * 2;
    if (FAILED(ID3D12Device_CreateQueryHeap(d12, &qh, &IID_ID3D12QueryHeap, (void **)&tsHeap))) tsHeap = NULL;
    if (tsHeap) {
        D3D12_HEAP_PROPERTIES hp = {0};
        hp.Type = D3D12_HEAP_TYPE_READBACK;
        D3D12_RESOURCE_DESC rd = {0};
        rd.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
        rd.Width = sizeof(UINT64) * FRAMES * 2; rd.Height = 1; rd.DepthOrArraySize = 1; rd.MipLevels = 1;
        rd.SampleDesc.Count = 1; rd.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
        if (FAILED(ID3D12Device_CreateCommittedResource(d12, &hp, D3D12_HEAP_FLAG_NONE, &rd, D3D12_RESOURCE_STATE_COPY_DEST, NULL,
                                                        &IID_ID3D12Resource, (void **)&tsReadback))) tsReadback = NULL;
    }
    return 1;
}

static void barrier(ID3D12Resource *r, D3D12_RESOURCE_STATES from, D3D12_RESOURCE_STATES to) {
    D3D12_RESOURCE_BARRIER b;
    memset(&b, 0, sizeof b);
    b.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    b.Transition.pResource = r;
    b.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    b.Transition.StateBefore = from;
    b.Transition.StateAfter = to;
    ID3D12GraphicsCommandList_ResourceBarrier(list, 1, &b);
}

/* Waits for the frame slot's previous use, then reads its GPU time. */
static void waitSlot(int slot) {
    if (frameFence[slot] && ID3D12Fence_GetCompletedValue(fence) < frameFence[slot]) {
        ID3D12Fence_SetEventOnCompletion(fence, frameFence[slot], fenceEvent);
        WaitForSingleObject(fenceEvent, 5000);
    }
    if (tsPending[slot] && tsReadback && tsFreq) {
        UINT64 *t = NULL;
        D3D12_RANGE rr = { sizeof(UINT64) * slot * 2, sizeof(UINT64) * (slot * 2 + 2) };
        if (SUCCEEDED(ID3D12Resource_Map(tsReadback, 0, &rr, (void **)&t))) {
            UINT64 b = t[slot * 2], e = t[slot * 2 + 1];
            D3D12_RANGE nw = {0, 0};
            ID3D12Resource_Unmap(tsReadback, 0, &nw);
            if (e > b) {
                double ms = (double)(e - b) * 1000.0 / (double)tsFreq;
                int r = tsReps[slot] > 0 ? tsReps[slot] : 1;
                if (ms > 0 && ms < 2000) {
                    if (gpuMeasure) {
                        gpuMsSum += ms / r; gpuMsCount++;
                        if (gpuSceneN < 30000) gpuSceneMs[gpuSceneN++] = (float)(ms / r);
                    } else { calibSum += ms; calibCount++; }
                }
            }
        }
    }
    tsPending[slot] = 0;
}

static void drawScene12(float t, float iters, ID3D12Resource *instBuf, int count, int full) {
    float c[4] = { t, iters, 0, 0 };
    ID3D12GraphicsCommandList_SetGraphicsRoot32BitConstants(list, 0, 4, c, 0);
    if (full) {
        ID3D12GraphicsCommandList_SetPipelineState(list, psoFull);
        ID3D12GraphicsCommandList_DrawInstanced(list, 3, 1, 0, 0);
    }
    D3D12_VERTEX_BUFFER_VIEW vb;
    vb.BufferLocation = ID3D12Resource_GetGPUVirtualAddress(instBuf);
    vb.SizeInBytes = sizeof(float) * 4 * count;
    vb.StrideInBytes = 16;
    ID3D12GraphicsCommandList_SetPipelineState(list, psoPart);
    ID3D12GraphicsCommandList_IASetVertexBuffers(list, 0, 1, &vb);
    ID3D12GraphicsCommandList_DrawInstanced(list, 6, count, 0, 0);
}

/* One frame: [scene] × reps (gpu) or the particle simulation's frame (mix). */
static int frame12(int mix, double t) {
    int slot = IDXGISwapChain3_GetCurrentBackBufferIndex(swap3);
    waitSlot(slot);
    if (mix) {
        /* Fixed time step: the work per frame never depends on the frame rate. */
        const float dt = 1.f / 60.f;
        float *o = mixMapped[slot];
        for (int i = 0; i < MIX_PARTICLES; i++) {
            float *s = sim + i * 4;
            float ax = -s[0] * 0.8f + 0.3f * sinf(s[1] * 3.1f + (float)t);
            float ay = -s[1] * 0.8f + 0.3f * cosf(s[0] * 2.7f - (float)t);
            s[2] = (s[2] + ax * dt) * 0.995f;
            s[3] = (s[3] + ay * dt) * 0.995f;
            s[0] += s[2] * dt;
            s[1] += s[3] * dt;
            if (s[0] < -1 || s[0] > 1) s[2] = -s[2];
            if (s[1] < -1 || s[1] > 1) s[3] = -s[3];
            o[i * 4] = s[0]; o[i * 4 + 1] = s[1]; o[i * 4 + 2] = 0.008f; o[i * 4 + 3] = (i % 89) / 89.f;
        }
    }
    ID3D12CommandAllocator_Reset(alloc[slot]);
    ID3D12GraphicsCommandList_Reset(list, alloc[slot], psoFull);
    ID3D12GraphicsCommandList_SetGraphicsRootSignature(list, rootSig);
    D3D12_VIEWPORT vp = { 0, 0, W, H, 0, 1 };
    D3D12_RECT sc = { 0, 0, W, H };
    ID3D12GraphicsCommandList_RSSetViewports(list, 1, &vp);
    ID3D12GraphicsCommandList_RSSetScissorRects(list, 1, &sc);
    ID3D12GraphicsCommandList_IASetPrimitiveTopology(list, D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    barrier(backBuf[slot], D3D12_RESOURCE_STATE_PRESENT, D3D12_RESOURCE_STATE_RENDER_TARGET);
    D3D12_CPU_DESCRIPTOR_HANDLE h;
    rtvHeap->lpVtbl->GetCPUDescriptorHandleForHeapStart(rtvHeap, &h);
    h.ptr += (SIZE_T)slot * rtvStep;
    ID3D12GraphicsCommandList_OMSetRenderTargets(list, 1, &h, FALSE, NULL);
    float black[4] = { 0, 0, 0, 1 };
    ID3D12GraphicsCommandList_ClearRenderTargetView(list, h, black, 0, NULL);
    int timed = tsHeap && tsReadback;
    if (timed) ID3D12GraphicsCommandList_EndQuery(list, tsHeap, D3D12_QUERY_TYPE_TIMESTAMP, slot * 2);
    if (mix) {
        drawScene12((float)t, 12.f, inst12Mix[slot], MIX_PARTICLES, 1);
        tsReps[slot] = 1;
    } else {
        for (int r = 0; r < reps; r++) {
            drawScene12((float)t + r, 64.f, inst12Gpu, GPU_PARTICLES, 1);
            drawScene12((float)t * 0.5f + r, 24.f, inst12Gpu, GPU_PARTICLES / 2, 1);
        }
        tsReps[slot] = reps;
    }
    if (timed) {
        ID3D12GraphicsCommandList_EndQuery(list, tsHeap, D3D12_QUERY_TYPE_TIMESTAMP, slot * 2 + 1);
        ID3D12GraphicsCommandList_ResolveQueryData(list, tsHeap, D3D12_QUERY_TYPE_TIMESTAMP, slot * 2, 2, tsReadback,
                                                  sizeof(UINT64) * slot * 2);
    }
    barrier(backBuf[slot], D3D12_RESOURCE_STATE_RENDER_TARGET, D3D12_RESOURCE_STATE_PRESENT);
    if (FAILED(ID3D12GraphicsCommandList_Close(list))) { strcpy(errorText, "command list"); return 0; }
    ID3D12CommandList *lists[1] = { (ID3D12CommandList *)list };
    ID3D12CommandQueue_ExecuteCommandLists(queue, 1, lists);
    if (FAILED(IDXGISwapChain3_Present(swap3, 0, 0))) { strcpy(errorText, "Present"); return 0; }
    fenceValue++;
    ID3D12CommandQueue_Signal(queue, fence, fenceValue);
    frameFence[slot] = fenceValue;
    tsPending[slot] = timed;
    return 1;
}

static void drain12(void) {
    for (int i = 0; i < FRAMES; i++) waitSlot(i);
}

static Stats run12(int mix, double warm, double len) {
    phase = mix ? "mix" : "gpu";
    phaseFrames = 0;
    gpuMsSum = 0; gpuMsCount = 0; gpuSceneN = 0;
    calibSum = 0; calibCount = 0; reps = 1;
    if (mix && !sim) {
        sim = (float *)malloc(sizeof(float) * 4 * MIX_PARTICLES);
        unsigned x = 999;
        for (int i = 0; i < MIX_PARTICLES; i++) {
            x = x * 1664525u + 1013904223u; sim[i * 4] = ((x >> 8) & 0xffff) / 32768.f - 1.f;
            x = x * 1664525u + 1013904223u; sim[i * 4 + 1] = ((x >> 8) & 0xffff) / 32768.f - 1.f;
            sim[i * 4 + 2] = 0; sim[i * 4 + 3] = 0;
        }
    }
    double start = now(), last = start, measureFrom = start + warm, nextTune = start + 0.5;
    int n = 0;
    while (1) {
        if (!pump()) break;
        double t = now();
        if (t - start > warm + len) break;
        gpuMeasure = t >= measureFrom;
        if (!mix && !gpuMeasure && t >= nextTune && calibCount >= 3) {
            double perFrame = calibSum / calibCount;
            int want = (int)(reps * 30.0 / (perFrame > 0.5 ? perFrame : 0.5) + 0.5);
            reps = want < 1 ? 1 : want > 24 ? 24 : want;
            calibSum = 0; calibCount = 0;
            nextTune = t + 0.5;
        }
        if (!frame12(mix, t)) break;
        double t2 = now();
        if (t2 >= measureFrom && n < MAX_FRAMES) frameTimes[n++] = (float)(t2 - last);
        last = t2;
        phaseFrames++;
    }
    gpuMeasure = 1;
    drain12();
    gpuMeasure = 0;
    Stats st = stats(n, now() - measureFrom);
    st.gpuSamples = gpuMsCount;
    st.gpuMs = gpuMsCount ? gpuMsSum / gpuMsCount : 0;
    st.gpuLow1 = mix ? 0 : sceneLow1();
    st.reps = mix ? 1 : reps;
    reps = 1;
    return st;
}
