/*
 * YurnBench: the fixed x86 benchmark the bundle finder runs in a container (YurNLator, GPL-3.0).
 *
 * Three phases, always the same scene and the same amount of work:
 *   cpu  - 2 s single thread, then 4 s on every core: integer + float mixing over a small array.
 *   gpu  - two heavy full-screen pixel shader passes and 180k large blended particles (fill rate and
 *          shader ALU); the CPU does almost nothing.
 *   mix  - 60k particles simulated on the CPU every frame, uploaded and drawn, plus a lighter shader.
 * Each GPU phase starts with 1.5 s of warm-up (shader compilation, DXVK pipelines) that is not counted.
 *
 * Next to the exe it writes status.json (a heartbeat every 0.5 s: the app's watchdog treats a
 * heartbeat that stops as a crash or a hang) and result.json at the end, then exits.
 * Build: x86_64-w64-mingw32-gcc -O2 -mwindows bench.c -ld3d11 -ldxgi -ld3dcompiler_47 -static
 */
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <d3dcompiler.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define W 1280
#define H 720
#define GPU_PARTICLES 120000
#define MIX_PARTICLES 60000
#define MAX_FRAMES 30000

static char dir[MAX_PATH];
static LARGE_INTEGER freq, t0;
static const char *phase = "start";
static int phaseFrames = 0;
static char errorText[512] = "";

static double now(void) {
    LARGE_INTEGER t;
    QueryPerformanceCounter(&t);
    return (double)(t.QuadPart - t0.QuadPart) / (double)freq.QuadPart;
}

static void path(char *out, const char *name) { snprintf(out, MAX_PATH, "%s\\%s", dir, name); }

static double lastBeat = -1;
static void heartbeat(int force) {
    double t = now();
    if (!force && t - lastBeat < 0.5) return;
    lastBeat = t;
    char p[MAX_PATH], tmp[MAX_PATH];
    path(p, "status.json");
    path(tmp, "status.tmp");
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    fprintf(f, "{\"phase\":\"%s\",\"t\":%.2f,\"frames\":%d}\n", phase, t, phaseFrames);
    fclose(f);
    MoveFileExA(tmp, p, MOVEFILE_REPLACE_EXISTING);
}

static int pump(void) {
    MSG m;
    while (PeekMessageA(&m, NULL, 0, 0, PM_REMOVE)) {
        if (m.message == WM_QUIT) return 0;
        TranslateMessage(&m);
        DispatchMessageA(&m);
    }
    heartbeat(0);
    return 1;
}

static LRESULT CALLBACK wndproc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcA(h, msg, wp, lp);
}

/* ---- frame statistics ---- */

typedef struct { double avg, low1, low01, frameMs; int frames; double seconds; } Stats;
static float frameTimes[MAX_FRAMES];

static int cmpDesc(const void *a, const void *b) {
    float x = *(const float *)a, y = *(const float *)b;
    return x < y ? 1 : x > y ? -1 : 0;
}

static Stats stats(int n, double seconds) {
    Stats s = {0};
    s.frames = n;
    s.seconds = seconds;
    if (n <= 0 || seconds <= 0) return s;
    s.avg = n / seconds;
    qsort(frameTimes, n, sizeof(float), cmpDesc);
    int k1 = n / 100 > 0 ? n / 100 : 1, k01 = n / 1000 > 0 ? n / 1000 : 1;
    double sum = 0;
    for (int i = 0; i < k1; i++) sum += frameTimes[i];
    s.low1 = sum > 0 ? k1 / sum : 0;
    sum = 0;
    for (int i = 0; i < k01; i++) sum += frameTimes[i];
    s.low01 = sum > 0 ? k01 / sum : 0;
    s.frameMs = 1000.0 * seconds / n;
    return s;
}

/* ---- CPU work ---- */

typedef struct { volatile LONG stop; volatile LONG64 ops; unsigned seed; } Worker;

static unsigned long long cpuChunk(unsigned *seed) {
    /* One unit: 4096 steps of xorshift + float mixing over a 16 KB array. */
    static __thread float buf[4096];
    unsigned x = *seed;
    float acc = 0.f;
    for (int i = 0; i < 4096; i++) {
        x ^= x << 13; x ^= x >> 17; x ^= x << 5;
        int j = x & 4095;
        float v = buf[j] * 0.999f + (float)(x & 255) * 0.0039f;
        buf[j] = v;
        acc += v * 1.0001f - acc * 0.5f;
    }
    *seed = x + (unsigned)acc;
    return 1;
}

static DWORD WINAPI cpuThread(LPVOID p) {
    Worker *w = (Worker *)p;
    LONG64 n = 0;
    while (!w->stop) { cpuChunk(&w->seed); n++; if ((n & 63) == 0) w->ops = n; }
    w->ops = n;
    return 0;
}

/* ---- D3D11 ---- */

static ID3D11Device *dev;
static ID3D11DeviceContext *ctx;
static IDXGISwapChain *swap;
static ID3D11RenderTargetView *rtv;
static ID3D11VertexShader *vsFull, *vsPart;
static ID3D11PixelShader *psHeavy, *psPart;
static ID3D11Buffer *cb, *instGpu, *instMix;
static ID3D11InputLayout *layout;
static ID3D11BlendState *blend;
static char adapterName[128] = "";

static const char *HLSL =
    "cbuffer C : register(b0) { float time; float iters; float2 pad; };\n"
    "struct VO { float4 pos : SV_Position; float2 uv : TEXCOORD0; float4 col : COLOR0; };\n"
    "VO vs_full(uint id : SV_VertexID) {\n"
    "  VO o; float2 uv = float2((id << 1) & 2, id & 2);\n"
    "  o.pos = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1); o.uv = uv; o.col = 1; return o; }\n"
    "float hash(float2 p) { return frac(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }\n"
    "float noise(float2 p) { float2 i = floor(p), f = frac(p); f = f * f * (3 - 2 * f);\n"
    "  return lerp(lerp(hash(i), hash(i + float2(1, 0)), f.x), lerp(hash(i + float2(0, 1)), hash(i + 1), f.x), f.y); }\n"
    "float4 ps_heavy(VO i) : SV_Target {\n"
    "  float2 p = i.uv * 6 + time * 0.1; float v = 0, a = 0.5;\n"
    "  [loop] for (int k = 0; k < (int)iters; k++) { v += a * noise(p); p = p * 2.03 + float2(1.7, 9.2); a *= 0.55;\n"
    "    if (a < 0.01) a = 0.5; }\n"
    "  return float4(0.2 + 0.6 * v, 0.3 + 0.4 * sin(v * 6 + time), 0.5 + 0.5 * cos(v * 3), 1); }\n"
    "struct PI { float4 p : INST; };\n"
    "VO vs_part(uint id : SV_VertexID, PI inst) {\n"
    "  float2 c[6] = { float2(-1,-1), float2(1,-1), float2(-1,1), float2(-1,1), float2(1,-1), float2(1,1) };\n"
    "  VO o; float2 q = c[id % 6]; o.pos = float4(inst.p.xy + q * inst.p.z * float2(0.5625, 1), 0, 1);\n"
    "  o.uv = q * 0.5 + 0.5; o.col = float4(frac(inst.p.w), frac(inst.p.w * 7.3), frac(inst.p.w * 3.1), 0.6); return o; }\n"
    "float4 ps_part(VO i) : SV_Target { float d = length(i.uv - 0.5) * 2; clip(1 - d);\n"
    "  return float4(i.col.rgb * (1 - d * d), i.col.a * (1 - d)); }\n";

static ID3DBlob *compile(const char *entry, const char *target) {
    ID3DBlob *code = NULL, *err = NULL;
    HRESULT hr = D3DCompile(HLSL, strlen(HLSL), "yurnbench", NULL, NULL, entry, target, 0, 0, &code, &err);
    if (FAILED(hr)) {
        snprintf(errorText, sizeof errorText, "shader %s: 0x%08lx %s", entry, (unsigned long)hr,
                 err ? (const char *)ID3D10Blob_GetBufferPointer(err) : "");
        for (char *c = errorText; *c; c++) if (*c == '"' || *c == '\\' || *c < 32) *c = ' ';
        return NULL;
    }
    return code;
}

static int initD3D(HWND hwnd) {
    DXGI_SWAP_CHAIN_DESC sd = {0};
    sd.BufferCount = 1;
    sd.BufferDesc.Width = W;
    sd.BufferDesc.Height = H;
    sd.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.OutputWindow = hwnd;
    sd.SampleDesc.Count = 1;
    sd.Windowed = TRUE;
    sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
    D3D_FEATURE_LEVEL levels[] = { D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0 };
    HRESULT hr = D3D11CreateDeviceAndSwapChain(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0, levels, 3,
                                               D3D11_SDK_VERSION, &sd, &swap, &dev, NULL, &ctx);
    if (FAILED(hr)) { snprintf(errorText, sizeof errorText, "D3D11CreateDeviceAndSwapChain 0x%08lx", (unsigned long)hr); return 0; }

    IDXGIDevice *xd = NULL;
    IDXGIAdapter *ad = NULL;
    if (SUCCEEDED(ID3D11Device_QueryInterface(dev, &IID_IDXGIDevice, (void **)&xd)) && SUCCEEDED(IDXGIDevice_GetAdapter(xd, &ad))) {
        DXGI_ADAPTER_DESC d;
        if (SUCCEEDED(IDXGIAdapter_GetDesc(ad, &d))) {
            int j = 0;
            for (int i = 0; d.Description[i] && j < (int)sizeof adapterName - 1; i++)
                if (d.Description[i] >= 32 && d.Description[i] < 127 && d.Description[i] != '"' && d.Description[i] != '\\')
                    adapterName[j++] = (char)d.Description[i];
            adapterName[j] = 0;
        }
        IDXGIAdapter_Release(ad);
    }
    if (xd) IDXGIDevice_Release(xd);

    ID3D11Texture2D *back = NULL;
    IDXGISwapChain_GetBuffer(swap, 0, &IID_ID3D11Texture2D, (void **)&back);
    ID3D11Device_CreateRenderTargetView(dev, (ID3D11Resource *)back, NULL, &rtv);
    ID3D11Texture2D_Release(back);

    ID3DBlob *b;
    if (!(b = compile("vs_full", "vs_4_0"))) return 0;
    ID3D11Device_CreateVertexShader(dev, ID3D10Blob_GetBufferPointer(b), ID3D10Blob_GetBufferSize(b), NULL, &vsFull);
    ID3D10Blob_Release(b);
    if (!(b = compile("vs_part", "vs_4_0"))) return 0;
    ID3D11Device_CreateVertexShader(dev, ID3D10Blob_GetBufferPointer(b), ID3D10Blob_GetBufferSize(b), NULL, &vsPart);
    D3D11_INPUT_ELEMENT_DESC ie = { "INST", 0, DXGI_FORMAT_R32G32B32A32_FLOAT, 0, 0, D3D11_INPUT_PER_INSTANCE_DATA, 1 };
    ID3D11Device_CreateInputLayout(dev, &ie, 1, ID3D10Blob_GetBufferPointer(b), ID3D10Blob_GetBufferSize(b), &layout);
    ID3D10Blob_Release(b);
    if (!(b = compile("ps_heavy", "ps_4_0"))) return 0;
    ID3D11Device_CreatePixelShader(dev, ID3D10Blob_GetBufferPointer(b), ID3D10Blob_GetBufferSize(b), NULL, &psHeavy);
    ID3D10Blob_Release(b);
    if (!(b = compile("ps_part", "ps_4_0"))) return 0;
    ID3D11Device_CreatePixelShader(dev, ID3D10Blob_GetBufferPointer(b), ID3D10Blob_GetBufferSize(b), NULL, &psPart);
    ID3D10Blob_Release(b);
    if (!vsFull || !vsPart || !psHeavy || !psPart || !layout) { strcpy(errorText, "shader objects"); return 0; }

    D3D11_BUFFER_DESC bd = {0};
    bd.ByteWidth = 16;
    bd.Usage = D3D11_USAGE_DYNAMIC;
    bd.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    bd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    ID3D11Device_CreateBuffer(dev, &bd, NULL, &cb);

    /* Particles for the GPU phase: fixed, generated once from a fixed seed. */
    float *p = (float *)malloc(sizeof(float) * 4 * (GPU_PARTICLES > MIX_PARTICLES ? GPU_PARTICLES : MIX_PARTICLES));
    unsigned x = 12345;
    for (int i = 0; i < GPU_PARTICLES; i++) {
        x = x * 1664525u + 1013904223u; p[i * 4 + 0] = ((x >> 8) & 0xffff) / 32768.f - 1.f;
        x = x * 1664525u + 1013904223u; p[i * 4 + 1] = ((x >> 8) & 0xffff) / 32768.f - 1.f;
        p[i * 4 + 2] = 0.02f + (i % 9) * 0.008f;   /* big blended sprites: heavy overdraw */
        p[i * 4 + 3] = (i % 97) / 97.f;
    }
    D3D11_SUBRESOURCE_DATA init = { p, 0, 0 };
    bd.ByteWidth = sizeof(float) * 4 * GPU_PARTICLES;
    bd.Usage = D3D11_USAGE_IMMUTABLE;
    bd.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    bd.CPUAccessFlags = 0;
    ID3D11Device_CreateBuffer(dev, &bd, &init, &instGpu);
    free(p);
    bd.ByteWidth = sizeof(float) * 4 * MIX_PARTICLES;
    bd.Usage = D3D11_USAGE_DYNAMIC;
    bd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    ID3D11Device_CreateBuffer(dev, &bd, NULL, &instMix);

    D3D11_BLEND_DESC bl = {0};
    bl.RenderTarget[0].BlendEnable = TRUE;
    bl.RenderTarget[0].SrcBlend = D3D11_BLEND_SRC_ALPHA;
    bl.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
    bl.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    bl.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
    bl.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_ZERO;
    bl.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    bl.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    ID3D11Device_CreateBlendState(dev, &bl, &blend);
    if (!cb || !instGpu || !instMix || !blend) { strcpy(errorText, "buffers"); return 0; }

    D3D11_VIEWPORT vp = { 0, 0, W, H, 0, 1 };
    ID3D11DeviceContext_RSSetViewports(ctx, 1, &vp);
    return 1;
}

static void setConstants(float time, float iters) {
    D3D11_MAPPED_SUBRESOURCE m;
    if (SUCCEEDED(ID3D11DeviceContext_Map(ctx, (ID3D11Resource *)cb, 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) {
        float *f = (float *)m.pData;
        f[0] = time; f[1] = iters; f[2] = 0; f[3] = 0;
        ID3D11DeviceContext_Unmap(ctx, (ID3D11Resource *)cb, 0);
    }
}

static void drawFull(float time, float iters) {
    setConstants(time, iters);
    ID3D11DeviceContext_OMSetRenderTargets(ctx, 1, &rtv, NULL);
    ID3D11DeviceContext_OMSetBlendState(ctx, NULL, NULL, 0xffffffff);
    ID3D11DeviceContext_IASetInputLayout(ctx, NULL);
    ID3D11DeviceContext_IASetPrimitiveTopology(ctx, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D11DeviceContext_VSSetShader(ctx, vsFull, NULL, 0);
    ID3D11DeviceContext_PSSetShader(ctx, psHeavy, NULL, 0);
    ID3D11DeviceContext_PSSetConstantBuffers(ctx, 0, 1, &cb);
    ID3D11DeviceContext_Draw(ctx, 3, 0);
}

static void drawParticles(ID3D11Buffer *inst, int count) {
    UINT stride = 16, offset = 0;
    ID3D11DeviceContext_OMSetBlendState(ctx, blend, NULL, 0xffffffff);
    ID3D11DeviceContext_IASetInputLayout(ctx, layout);
    ID3D11DeviceContext_IASetVertexBuffers(ctx, 0, 1, &inst, &stride, &offset);
    ID3D11DeviceContext_VSSetShader(ctx, vsPart, NULL, 0);
    ID3D11DeviceContext_PSSetShader(ctx, psPart, NULL, 0);
    ID3D11DeviceContext_DrawInstanced(ctx, 6, count, 0, 0);
}

static void clear(float r, float g, float b) {
    float c[4] = { r, g, b, 1 };
    ID3D11DeviceContext_ClearRenderTargetView(ctx, rtv, c);
}

/* ---- phases ---- */

static double cpuSt = 0, cpuMt = 0;
static int cores = 1;

static int runCpu(void) {
    phase = "cpu";
    Worker w[64];
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    cores = si.dwNumberOfProcessors > 64 ? 64 : (int)si.dwNumberOfProcessors;
    if (cores < 1) cores = 1;
    for (int pass = 0; pass < 2; pass++) {
        int n = pass == 0 ? 1 : cores;
        double len = pass == 0 ? 2.0 : 4.0;
        HANDLE th[64];
        for (int i = 0; i < n; i++) { w[i].stop = 0; w[i].ops = 0; w[i].seed = 777 + i; th[i] = CreateThread(NULL, 0, cpuThread, &w[i], 0, NULL); }
        double start = now();
        while (now() - start < len) {
            if (!pump()) return 0;
            clear(0.05f, 0.05f, 0.1f + 0.1f * (float)pass);
            IDXGISwapChain_Present(swap, 1, 0);
            Sleep(30);
        }
        double secs = now() - start;
        for (int i = 0; i < n; i++) w[i].stop = 1;
        WaitForMultipleObjects(n, th, TRUE, 5000);
        LONG64 total = 0;
        for (int i = 0; i < n; i++) { total += w[i].ops; CloseHandle(th[i]); }
        double mops = total * 4096.0 / secs / 1e6;
        if (pass == 0) cpuSt = mops; else cpuMt = mops;
    }
    return 1;
}

static Stats runGpu(double warm, double len) {
    phase = "gpu";
    phaseFrames = 0;
    double start = now(), last = start, measureFrom = start + warm;
    int n = 0;
    while (1) {
        if (!pump()) break;
        double t = now();
        if (t - start > warm + len) break;
        clear(0, 0, 0);
        drawFull((float)t, 64.f);
        drawParticles(instGpu, GPU_PARTICLES);
        drawFull((float)t * 0.5f, 24.f);
        drawParticles(instGpu, GPU_PARTICLES / 2);
        IDXGISwapChain_Present(swap, 0, 0);
        double t2 = now();
        if (t2 >= measureFrom && n < MAX_FRAMES) frameTimes[n++] = (float)(t2 - last);
        last = t2;
        phaseFrames++;
    }
    return stats(n, now() - measureFrom);
}

static float *sim;      /* x, y, vx, vy per particle */

static Stats runMix(double warm, double len) {
    phase = "mix";
    phaseFrames = 0;
    if (!sim) {
        sim = (float *)malloc(sizeof(float) * 4 * MIX_PARTICLES);
        unsigned x = 999;
        for (int i = 0; i < MIX_PARTICLES; i++) {
            x = x * 1664525u + 1013904223u; sim[i * 4] = ((x >> 8) & 0xffff) / 32768.f - 1.f;
            x = x * 1664525u + 1013904223u; sim[i * 4 + 1] = ((x >> 8) & 0xffff) / 32768.f - 1.f;
            sim[i * 4 + 2] = 0; sim[i * 4 + 3] = 0;
        }
    }
    double start = now(), last = start, measureFrom = start + warm;
    int n = 0;
    while (1) {
        if (!pump()) break;
        double t = now();
        if (t - start > warm + len) break;
        /* Fixed time step so the work per frame never depends on the frame rate. */
        const float dt = 1.f / 60.f;
        D3D11_MAPPED_SUBRESOURCE m;
        if (FAILED(ID3D11DeviceContext_Map(ctx, (ID3D11Resource *)instMix, 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) break;
        float *o = (float *)m.pData;
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
        ID3D11DeviceContext_Unmap(ctx, (ID3D11Resource *)instMix, 0);
        clear(0, 0, 0);
        drawFull((float)t, 12.f);
        drawParticles(instMix, MIX_PARTICLES);
        IDXGISwapChain_Present(swap, 0, 0);
        double t2 = now();
        if (t2 >= measureFrom && n < MAX_FRAMES) frameTimes[n++] = (float)(t2 - last);
        last = t2;
        phaseFrames++;
    }
    return stats(n, now() - measureFrom);
}

static void writeStats(FILE *f, const char *name, Stats s) {
    fprintf(f, "\"%s\":{\"avg\":%.2f,\"low1\":%.2f,\"low01\":%.2f,\"frame_ms\":%.3f,\"frames\":%d,\"seconds\":%.2f}",
            name, s.avg, s.low1, s.low01, s.frameMs, s.frames, s.seconds);
}

static void writeResult(int ok, Stats g, Stats x) {
    char p[MAX_PATH], tmp[MAX_PATH];
    path(p, "result.json");
    path(tmp, "result.tmp");
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    fprintf(f, "{\"version\":2,\"done\":%s,\"error\":\"%s\",\"adapter\":\"%s\",\"cores\":%d,",
            ok ? "true" : "false", errorText, adapterName, cores);
    fprintf(f, "\"cpu\":{\"st\":%.1f,\"mt\":%.1f},", cpuSt, cpuMt);
    writeStats(f, "gpu", g);
    fputc(',', f);
    writeStats(f, "mix", x);
    fprintf(f, ",\"total_s\":%.2f}\n", now());
    fclose(f);
    MoveFileExA(tmp, p, MOVEFILE_REPLACE_EXISTING);
}

int WINAPI WinMain(HINSTANCE inst, HINSTANCE prev, LPSTR cmd, int show) {
    (void)prev; (void)cmd; (void)show;
    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&t0);
    GetModuleFileNameA(NULL, dir, MAX_PATH);
    char *slash = strrchr(dir, '\\');
    if (slash) *slash = 0;
    heartbeat(1);

    WNDCLASSA wc = {0};
    wc.lpfnWndProc = wndproc;
    wc.hInstance = inst;
    wc.lpszClassName = "YurnBench";
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    RegisterClassA(&wc);
    HWND hwnd = CreateWindowExA(0, "YurnBench", "YurnBench", WS_POPUP | WS_VISIBLE, 0, 0, W, H, NULL, NULL, inst, NULL);
    Stats none = {0};
    phase = "init";
    if (!hwnd) { strcpy(errorText, "CreateWindow"); writeResult(0, none, none); return 1; }
    if (!initD3D(hwnd)) { heartbeat(1); writeResult(0, none, none); return 2; }

    if (!runCpu()) { writeResult(0, none, none); return 3; }
    Stats g = runGpu(1.5, 8.0);
    Stats x = runMix(1.5, 8.0);
    phase = "done";
    heartbeat(1);
    writeResult(g.frames > 0 && x.frames > 0, g, x);
    ExitProcess(0);
    return 0;
}
