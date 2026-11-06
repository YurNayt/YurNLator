/* azhook: moves Azimuth's fixed reservations at 0x600'0000'0000 into the
 * 39-bit address space of Android (Winlator / Wine).
 * Freestanding: no CRT, no Windows SDK headers. */
#include <stdarg.h>

typedef unsigned long long u64;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
typedef long NTSTATUS;
typedef int BOOL;
typedef void *HANDLE;
typedef void *HMODULE;
typedef unsigned short WCHAR;
typedef u64 SIZE_T;
#define WINAPI __stdcall
#define IMP __declspec(dllimport)

/* ---------------------------------------------------- kernel32 imports */
IMP void *WINAPI GetProcAddress(HMODULE, const char *);
IMP HMODULE WINAPI GetModuleHandleA(const char *);
IMP u32 WINAPI GetModuleFileNameW(HMODULE, WCHAR *, u32);
IMP BOOL WINAPI K32EnumProcessModules(HANDLE, HMODULE *, u32, u32 *);
IMP HANDLE WINAPI GetCurrentProcess(void);
IMP u32 WINAPI GetCurrentThreadId(void);
IMP HANDLE WINAPI CreateFileW(const WCHAR *, u32, u32, void *, u32, u32, HANDLE);
IMP BOOL WINAPI WriteFile(HANDLE, const void *, u32, u32 *, void *);
IMP BOOL WINAPI FlushFileBuffers(HANDLE);
IMP u32 WINAPI GetEnvironmentVariableA(const char *, char *, u32);
IMP void WINAPI InitializeCriticalSection(void *);
IMP void WINAPI EnterCriticalSection(void *);
IMP void WINAPI LeaveCriticalSection(void *);
IMP BOOL WINAPI DisableThreadLibraryCalls(HMODULE);
IMP void *WINAPI AddVectoredExceptionHandler(u32, void *);
IMP BOOL WINAPI GetModuleHandleExW(u32, const void *, HMODULE *);

/* ------------------------------------------------------- tiny libc */
void *memset(void *d, int c, SIZE_T n) { u8 *p = d; while (n--) *p++ = (u8)c; return d; }
void *memcpy(void *d, const void *s, SIZE_T n) { u8 *p = d; const u8 *q = s; while (n--) *p++ = *q++; return d; }
int _fltused = 0;

static int streq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
static WCHAR lw(WCHAR c) { return (c >= 'A' && c <= 'Z') ? c + 32 : c; }
static int wcontains_ci(const WCHAR *h, const char *n)
{
   for (; *h; h++) {
      const WCHAR *a = h; const char *b = n;
      while (*a && *b && lw(*a) == (WCHAR)*b) { a++; b++; }
      if (!*b) return 1;
   }
   return 0;
}

/* ------------------------------------------------------------ config */
static u64 HI_BASE = 0x60000000000ull;
static u64 HI_END  = 0x61000000000ull;     /* 64 GB window */
static u64 LO_BASE = 0x4000000000ull;      /* 256 GB, inside 39-bit space */

static u64 parse_hex(const char *s)
{
   u64 v = 0;
   if (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s += 2;
   for (; *s; s++) {
      int d;
      if (*s >= '0' && *s <= '9') d = *s - '0';
      else if (*s >= 'a' && *s <= 'f') d = *s - 'a' + 10;
      else if (*s >= 'A' && *s <= 'F') d = *s - 'A' + 10;
      else break;
      v = v * 16 + d;
   }
   return v;
}

static int in_win(u64 p) { return p >= HI_BASE && p < HI_END; }
static void *tr(void *p) { u64 v = (u64)p; return in_win(v) ? (void *)(v - HI_BASE + LO_BASE) : p; }

/* ------------------------------------------------------------- log */
static HANDLE g_log = (HANDLE)-1;
static u8 g_cs[64];
static int g_cs_ok;

static void out(char *buf, int *n, char c) { if (*n < 1000) buf[(*n)++] = c; }
static void out_hex(char *buf, int *n, u64 v)
{
   char t[20]; int i = 0;
   out(buf, n, '0'); out(buf, n, 'x');
   do { int d = v & 15; t[i++] = d < 10 ? '0' + d : 'a' + d - 10; v >>= 4; } while (v);
   while (i) out(buf, n, t[--i]);
}
static void out_dec(char *buf, int *n, u64 v)
{
   char t[24]; int i = 0;
   do { t[i++] = '0' + v % 10; v /= 10; } while (v);
   while (i) out(buf, n, t[--i]);
}

/* %x = hex u64, %d = dec u64, %s = char*, %S = WCHAR* */
static void logf(const char *fmt, ...)
{
   if (g_log == (HANDLE)-1) return;
   char buf[1024]; int n = 0;
   va_list ap; va_start(ap, fmt);
   out_dec(buf, &n, GetCurrentThreadId()); out(buf, &n, ':'); out(buf, &n, ' ');
   for (; *fmt; fmt++) {
      if (*fmt != '%') { out(buf, &n, *fmt); continue; }
      fmt++;
      if (*fmt == 'x') out_hex(buf, &n, va_arg(ap, u64));
      else if (*fmt == 'd') out_dec(buf, &n, va_arg(ap, u64));
      else if (*fmt == 's') { const char *s = va_arg(ap, const char *); if (!s) s = "(null)"; while (*s) out(buf, &n, *s++); }
      else if (*fmt == 'S') { const WCHAR *s = va_arg(ap, const WCHAR *); while (s && *s) { out(buf, &n, *s < 128 ? (char)*s : '?'); s++; } }
      else out(buf, &n, *fmt);
   }
   va_end(ap);
   out(buf, &n, '\r'); out(buf, &n, '\n');
   u32 w;
   if (g_cs_ok) EnterCriticalSection(g_cs);
   WriteFile(g_log, buf, n, &w, 0);
   FlushFileBuffers(g_log);
   if (g_cs_ok) LeaveCriticalSection(g_cs);
}

#define BIG (256ull << 20)
#define HIGH 0x7f00000000ull
static unsigned g_nreg;
static int in_reg(u64 p) { return p >= 0x800000000ull && p < 0xd00000000ull; }
static int interesting(u64 in, u64 size) { return in_win(in) || in >= HIGH || size >= BIG || (in_reg(in) && g_nreg++ < 3000); }

/* ------------------------------------------------------- originals */
typedef void *(WINAPI *pVirtualAlloc)(void *, SIZE_T, u32, u32);
typedef void *(WINAPI *pVirtualAllocEx)(HANDLE, void *, SIZE_T, u32, u32);
typedef void *(WINAPI *pVirtualAlloc2)(HANDLE, void *, SIZE_T, u32, u32, void *, u32);
typedef BOOL (WINAPI *pVirtualFree)(void *, SIZE_T, u32);
typedef BOOL (WINAPI *pVirtualFreeEx)(HANDLE, void *, SIZE_T, u32);
typedef BOOL (WINAPI *pVirtualProtect)(void *, SIZE_T, u32, u32 *);
typedef BOOL (WINAPI *pVirtualProtectEx)(HANDLE, void *, SIZE_T, u32, u32 *);
typedef SIZE_T (WINAPI *pVirtualQuery)(const void *, void *, SIZE_T);
typedef SIZE_T (WINAPI *pVirtualQueryEx)(HANDLE, const void *, void *, SIZE_T);
typedef void *(WINAPI *pMapViewOfFileEx)(HANDLE, u32, u32, u32, SIZE_T, void *);
typedef void *(WINAPI *pMapViewOfFile3)(HANDLE, HANDLE, void *, u64, SIZE_T, u32, u32, void *, u32);
typedef BOOL (WINAPI *pUnmapViewOfFile)(const void *);
typedef BOOL (WINAPI *pUnmapViewOfFileEx)(void *, u32);
typedef BOOL (WINAPI *pUnmapViewOfFile2)(HANDLE, void *, u32);
typedef NTSTATUS (WINAPI *pNtAllocateVirtualMemory)(HANDLE, void **, u64, SIZE_T *, u32, u32);
typedef NTSTATUS (WINAPI *pNtAllocateVirtualMemoryEx)(HANDLE, void **, SIZE_T *, u32, u32, void *, u32);
typedef NTSTATUS (WINAPI *pNtFreeVirtualMemory)(HANDLE, void **, SIZE_T *, u32);
typedef NTSTATUS (WINAPI *pNtProtectVirtualMemory)(HANDLE, void **, SIZE_T *, u32, u32 *);
typedef NTSTATUS (WINAPI *pNtQueryVirtualMemory)(HANDLE, void *, u32, void *, SIZE_T, SIZE_T *);
typedef NTSTATUS (WINAPI *pNtMapViewOfSection)(HANDLE, HANDLE, void **, u64, SIZE_T, void *, SIZE_T *, u32, u32, u32);
typedef NTSTATUS (WINAPI *pNtMapViewOfSectionEx)(HANDLE, HANDLE, void **, void *, SIZE_T *, u32, u32, void *, u32);
typedef NTSTATUS (WINAPI *pNtUnmapViewOfSection)(HANDLE, void *);
typedef NTSTATUS (WINAPI *pNtUnmapViewOfSectionEx)(HANDLE, void *, u32);
typedef void *(WINAPI *pGetProcAddress)(HMODULE, const char *);
typedef HMODULE (WINAPI *pLoadLibraryA)(const char *);
typedef BOOL (WINAPI *pGlobalMemoryStatusEx)(void *);
typedef BOOL (WINAPI *pGetPhysicallyInstalledSystemMemory)(u64 *);
typedef HMODULE (WINAPI *pLoadLibraryW)(const WCHAR *);
typedef HMODULE (WINAPI *pLoadLibraryExA)(const char *, HANDLE, u32);
typedef HMODULE (WINAPI *pLoadLibraryExW)(const WCHAR *, HANDLE, u32);

static pVirtualAlloc oVirtualAlloc; static pVirtualAllocEx oVirtualAllocEx; static pVirtualAlloc2 oVirtualAlloc2;
static pVirtualFree oVirtualFree; static pVirtualFreeEx oVirtualFreeEx;
static pVirtualProtect oVirtualProtect; static pVirtualProtectEx oVirtualProtectEx;
static pVirtualQuery oVirtualQuery; static pVirtualQueryEx oVirtualQueryEx;
static pMapViewOfFileEx oMapViewOfFileEx; static pMapViewOfFile3 oMapViewOfFile3;
static pUnmapViewOfFile oUnmapViewOfFile; static pUnmapViewOfFileEx oUnmapViewOfFileEx; static pUnmapViewOfFile2 oUnmapViewOfFile2;
static pNtAllocateVirtualMemory oNtAllocateVirtualMemory; static pNtAllocateVirtualMemoryEx oNtAllocateVirtualMemoryEx;
static pNtFreeVirtualMemory oNtFreeVirtualMemory; static pNtProtectVirtualMemory oNtProtectVirtualMemory;
static pNtQueryVirtualMemory oNtQueryVirtualMemory;
static pNtMapViewOfSection oNtMapViewOfSection; static pNtMapViewOfSectionEx oNtMapViewOfSectionEx;
static pNtUnmapViewOfSection oNtUnmapViewOfSection; static pNtUnmapViewOfSectionEx oNtUnmapViewOfSectionEx;
static pGetProcAddress oGetProcAddress;
static pGlobalMemoryStatusEx oGlobalMemoryStatusEx; static pGetPhysicallyInstalledSystemMemory oGetPhysicallyInstalledSystemMemory;
static u64 g_fake_ram;   /* TLOUHOOK_RAM_GB: report this much RAM */
static unsigned g_nmem;
static pLoadLibraryA oLoadLibraryA; static pLoadLibraryW oLoadLibraryW;
static pLoadLibraryExA oLoadLibraryExA; static pLoadLibraryExW oLoadLibraryExW;

static void patch_all(void);


/* MEM_EXTENDED_PARAMETER / MEM_ADDRESS_REQUIREMENTS: the game asks for
 * HighestEndingAddress = 0xffffffffff (40 bit); Wine rejects anything above
 * its 39-bit user limit ("Invalid limit"). Clamp to a 39-bit value. */
struct ext_param { u64 type; u64 val; };
struct addr_req { u64 lowest; u64 highest; u64 align; };
#define MAX_HIGHEST 0x7ffeffffffull
static unsigned g_nclamp;
static void *fix_params(void *ep, u32 n, struct ext_param *cp, struct addr_req *rq)
{
   if (!ep || !n || n > 16) return ep;
   struct ext_param *src = ep;
   int changed = 0;
   for (u32 i = 0; i < n; i++) {
      cp[i] = src[i];
      if ((cp[i].type & 0xff) == 1 && cp[i].val) {
         struct addr_req *r = (struct addr_req *)cp[i].val;
         if (r->highest > MAX_HIGHEST || in_win(r->lowest)) {
            rq[i] = *r;
            if (rq[i].highest > MAX_HIGHEST) rq[i].highest = MAX_HIGHEST;
            if (in_win(rq[i].lowest)) rq[i].lowest = (u64)tr((void *)rq[i].lowest);
            if (rq[i].lowest && rq[i].lowest >= rq[i].highest) rq[i].lowest = 0;
            if (g_nclamp++ < 20)
               logf("  clamp addr req low %x high %x align %x -> low %x high %x",
                    r->lowest, r->highest, r->align, rq[i].lowest, rq[i].highest);
            cp[i].val = (u64)&rq[i];
            changed = 1;
         }
      }
   }
   return changed ? (void *)cp : ep;
}
#define FIXP(ep, n) struct ext_param cp_[16]; struct addr_req rq_[16]; ep = fix_params(ep, n, cp_, rq_)

/* ----------------------------------------------------------- hooks */
static void *WINAPI hVirtualAlloc(void *a, SIZE_T s, u32 t, u32 p)
{
   void *r = oVirtualAlloc(tr(a), s, t, p);
   if (interesting((u64)a, s)) logf("VirtualAlloc %x -> %x size %x type %x prot %x = %x", (u64)a, (u64)tr(a), s, (u64)t, (u64)p, (u64)r);
   return r;
}
static void *WINAPI hVirtualAllocEx(HANDLE h, void *a, SIZE_T s, u32 t, u32 p)
{
   void *r = oVirtualAllocEx(h, tr(a), s, t, p);
   if (interesting((u64)a, s)) logf("VirtualAllocEx %x -> %x size %x type %x prot %x = %x", (u64)a, (u64)tr(a), s, (u64)t, (u64)p, (u64)r);
   return r;
}
static void *WINAPI hVirtualAlloc2(HANDLE h, void *a, SIZE_T s, u32 t, u32 p, void *ep, u32 n)
{
   FIXP(ep, n);
   void *r = oVirtualAlloc2(h, tr(a), s, t, p, ep, n);
   if (interesting((u64)a, s) || (!r && g_nclamp < 40) || (in_reg((u64)r) && g_nreg++ < 3000)) logf("VirtualAlloc2 %x -> %x size %x type %x prot %x params %d = %x", (u64)a, (u64)tr(a), s, (u64)t, (u64)p, (u64)n, (u64)r);
   return r;
}
static BOOL WINAPI hVirtualFree(void *a, SIZE_T s, u32 t)
{
   BOOL r = oVirtualFree(tr(a), s, t);
   if (interesting((u64)a, s)) logf("VirtualFree %x -> %x size %x type %x = %d", (u64)a, (u64)tr(a), s, (u64)t, (u64)r);
   return r;
}
static BOOL WINAPI hVirtualFreeEx(HANDLE h, void *a, SIZE_T s, u32 t)
{
   BOOL r = oVirtualFreeEx(h, tr(a), s, t);
   if (interesting((u64)a, s)) logf("VirtualFreeEx %x -> %x size %x type %x = %d", (u64)a, (u64)tr(a), s, (u64)t, (u64)r);
   return r;
}
static BOOL WINAPI hVirtualProtect(void *a, SIZE_T s, u32 p, u32 *o)
{
   BOOL r = oVirtualProtect(tr(a), s, p, o);
   if (interesting((u64)a, s)) logf("VirtualProtect %x -> %x size %x prot %x = %d", (u64)a, (u64)tr(a), s, (u64)p, (u64)r);
   return r;
}
static BOOL WINAPI hVirtualProtectEx(HANDLE h, void *a, SIZE_T s, u32 p, u32 *o)
{
   BOOL r = oVirtualProtectEx(h, tr(a), s, p, o);
   if (interesting((u64)a, s)) logf("VirtualProtectEx %x -> %x size %x prot %x = %d", (u64)a, (u64)tr(a), s, (u64)p, (u64)r);
   return r;
}
static SIZE_T WINAPI hVirtualQuery(const void *a, void *m, SIZE_T l)
{
   SIZE_T r = oVirtualQuery(tr((void *)a), m, l);
   if (in_win((u64)a)) logf("VirtualQuery %x -> %x = %d", (u64)a, (u64)tr((void *)a), r);
   return r;
}
static SIZE_T WINAPI hVirtualQueryEx(HANDLE h, const void *a, void *m, SIZE_T l)
{
   SIZE_T r = oVirtualQueryEx(h, tr((void *)a), m, l);
   if (in_win((u64)a)) logf("VirtualQueryEx %x -> %x = %d", (u64)a, (u64)tr((void *)a), r);
   return r;
}
static void *WINAPI hMapViewOfFileEx(HANDLE m, u32 acc, u32 hi, u32 lo, SIZE_T s, void *a)
{
   void *r = oMapViewOfFileEx(m, acc, hi, lo, s, tr(a));
   if (interesting((u64)a, s)) logf("MapViewOfFileEx %x -> %x size %x = %x", (u64)a, (u64)tr(a), s, (u64)r);
   return r;
}
static void *WINAPI hMapViewOfFile3(HANDLE f, HANDLE pr, void *a, u64 off, SIZE_T s, u32 t, u32 p, void *ep, u32 n)
{
   FIXP(ep, n);
   void *r = oMapViewOfFile3(f, pr, tr(a), off, s, t, p, ep, n);
   if (interesting((u64)a, s) || !r || (in_reg((u64)r) && g_nreg++ < 3000)) logf("MapViewOfFile3 %x -> %x off %x size %x type %x prot %x = %x", (u64)a, (u64)tr(a), off, s, (u64)t, (u64)p, (u64)r);
   return r;
}
static BOOL WINAPI hUnmapViewOfFile(const void *a)
{
   BOOL r = oUnmapViewOfFile(tr((void *)a));
   if (in_win((u64)a) || in_reg((u64)a)) logf("UnmapViewOfFile %x = %d", (u64)a, (u64)r);
   return r;
}
static BOOL WINAPI hUnmapViewOfFileEx(void *a, u32 f)
{
   BOOL r = oUnmapViewOfFileEx(tr(a), f);
   if (in_win((u64)a) || in_reg((u64)a)) logf("UnmapViewOfFileEx %x flags %x = %d", (u64)a, (u64)f, (u64)r);
   return r;
}
static BOOL WINAPI hUnmapViewOfFile2(HANDLE h, void *a, u32 f)
{
   BOOL r = oUnmapViewOfFile2(h, tr(a), f);
   if (in_win((u64)a) || in_reg((u64)a)) logf("UnmapViewOfFile2 %x flags %x = %d", (u64)a, (u64)f, (u64)r);
   return r;
}

#define TRB(b) u64 in = (b) ? (u64)*(b) : 0; if ((b) && in_win(in)) *(b) = tr(*(b))
static NTSTATUS WINAPI hNtAllocateVirtualMemory(HANDLE h, void **b, u64 z, SIZE_T *s, u32 t, u32 p)
{
   TRB(b);
   NTSTATUS r = oNtAllocateVirtualMemory(h, b, z, s, t, p);
   if (interesting(in, s ? *s : 0)) logf("NtAllocateVirtualMemory %x -> %x size %x type %x prot %x = %x", in, b ? (u64)*b : 0, s ? *s : 0, (u64)t, (u64)p, (u64)(u32)r);
   return r;
}
static NTSTATUS WINAPI hNtAllocateVirtualMemoryEx(HANDLE h, void **b, SIZE_T *s, u32 t, u32 p, void *ep, u32 n)
{
   TRB(b);
   FIXP(ep, n);
   NTSTATUS r = oNtAllocateVirtualMemoryEx(h, b, s, t, p, ep, n);
   if (interesting(in, s ? *s : 0)) logf("NtAllocateVirtualMemoryEx %x -> %x size %x type %x prot %x params %d = %x", in, b ? (u64)*b : 0, s ? *s : 0, (u64)t, (u64)p, (u64)n, (u64)(u32)r);
   return r;
}
static NTSTATUS WINAPI hNtFreeVirtualMemory(HANDLE h, void **b, SIZE_T *s, u32 t)
{
   TRB(b);
   NTSTATUS r = oNtFreeVirtualMemory(h, b, s, t);
   if (interesting(in, s ? *s : 0)) logf("NtFreeVirtualMemory %x -> %x size %x type %x = %x", in, b ? (u64)*b : 0, s ? *s : 0, (u64)t, (u64)(u32)r);
   return r;
}
static NTSTATUS WINAPI hNtProtectVirtualMemory(HANDLE h, void **b, SIZE_T *s, u32 p, u32 *o)
{
   TRB(b);
   NTSTATUS r = oNtProtectVirtualMemory(h, b, s, p, o);
   if (interesting(in, s ? *s : 0)) logf("NtProtectVirtualMemory %x -> %x size %x prot %x = %x", in, b ? (u64)*b : 0, s ? *s : 0, (u64)p, (u64)(u32)r);
   return r;
}
static NTSTATUS WINAPI hNtQueryVirtualMemory(HANDLE h, void *a, u32 c, void *i, SIZE_T l, SIZE_T *rl)
{
   NTSTATUS r = oNtQueryVirtualMemory(h, tr(a), c, i, l, rl);
   if (in_win((u64)a)) logf("NtQueryVirtualMemory %x -> %x class %d = %x", (u64)a, (u64)tr(a), (u64)c, (u64)(u32)r);
   return r;
}
static NTSTATUS WINAPI hNtMapViewOfSection(HANDLE sec, HANDLE h, void **b, u64 z, SIZE_T cs, void *off, SIZE_T *vs, u32 inh, u32 t, u32 p)
{
   TRB(b);
   NTSTATUS r = oNtMapViewOfSection(sec, h, b, z, cs, off, vs, inh, t, p);
   if (interesting(in, vs ? *vs : 0)) logf("NtMapViewOfSection %x -> %x size %x type %x prot %x = %x", in, b ? (u64)*b : 0, vs ? *vs : 0, (u64)t, (u64)p, (u64)(u32)r);
   return r;
}
static NTSTATUS WINAPI hNtMapViewOfSectionEx(HANDLE sec, HANDLE h, void **b, void *off, SIZE_T *vs, u32 t, u32 p, void *ep, u32 n)
{
   TRB(b);
   FIXP(ep, n);
   NTSTATUS r = oNtMapViewOfSectionEx(sec, h, b, off, vs, t, p, ep, n);
   if (interesting(in, vs ? *vs : 0)) logf("NtMapViewOfSectionEx %x -> %x size %x type %x prot %x = %x", in, b ? (u64)*b : 0, vs ? *vs : 0, (u64)t, (u64)p, (u64)(u32)r);
   return r;
}
static NTSTATUS WINAPI hNtUnmapViewOfSection(HANDLE h, void *a)
{
   NTSTATUS r = oNtUnmapViewOfSection(h, tr(a));
   if (in_win((u64)a) || in_reg((u64)a)) logf("NtUnmapViewOfSection %x = %x", (u64)a, (u64)(u32)r);
   return r;
}
static NTSTATUS WINAPI hNtUnmapViewOfSectionEx(HANDLE h, void *a, u32 f)
{
   NTSTATUS r = oNtUnmapViewOfSectionEx(h, tr(a), f);
   if (in_win((u64)a) || in_reg((u64)a)) logf("NtUnmapViewOfSectionEx %x = %x", (u64)a, (u64)(u32)r);
   return r;
}

static BOOL WINAPI hGlobalMemoryStatusEx(void *b)
{
   BOOL r = oGlobalMemoryStatusEx(b);
   u64 *q = (u64 *)((u8 *)b + 8);   /* TotalPhys, AvailPhys, TotalPageFile, AvailPageFile, TotalVirtual, AvailVirtual */
   if (r && g_fake_ram) {
      if (q[0] < g_fake_ram) { q[1] += g_fake_ram - q[0]; q[0] = g_fake_ram; }
      if (q[2] < g_fake_ram * 2) { q[3] += g_fake_ram * 2 - q[2]; q[2] = g_fake_ram * 2; }
   }
   if (r && g_nmem++ < 10) logf("GlobalMemoryStatusEx: total %d MB avail %d MB pagefile %d MB virtual %d MB",
                                q[0] >> 20, q[1] >> 20, q[2] >> 20, q[4] >> 20);
   return r;
}
static BOOL WINAPI hGetPhysicallyInstalledSystemMemory(u64 *kb)
{
   BOOL r = oGetPhysicallyInstalledSystemMemory(kb);
   if (r && kb && g_fake_ram && *kb < (g_fake_ram >> 10)) *kb = g_fake_ram >> 10;
   logf("GetPhysicallyInstalledSystemMemory = %d (%d KB)", (u64)r, kb ? *kb : 0);
   return r;
}

/* ---------------------------------------------------- hook table */
struct hook { const char *name; void *fn; void **orig; };
#define H(n) { #n, (void *)h##n, (void **)&o##n }
static void *WINAPI hGetProcAddress(HMODULE m, const char *n);
static HMODULE WINAPI hLoadLibraryA(const char *n);
static HMODULE WINAPI hLoadLibraryW(const WCHAR *n);
static HMODULE WINAPI hLoadLibraryExA(const char *n, HANDLE f, u32 fl);
static HMODULE WINAPI hLoadLibraryExW(const WCHAR *n, HANDLE f, u32 fl);
static struct hook g_hooks[] = {
   H(VirtualAlloc), H(VirtualAllocEx), H(VirtualAlloc2), H(VirtualFree), H(VirtualFreeEx),
   H(VirtualProtect), H(VirtualProtectEx), H(VirtualQuery), H(VirtualQueryEx),
   H(MapViewOfFileEx), H(MapViewOfFile3), H(UnmapViewOfFile), H(UnmapViewOfFileEx), H(UnmapViewOfFile2),
   H(NtAllocateVirtualMemory), H(NtAllocateVirtualMemoryEx), H(NtFreeVirtualMemory),
   H(NtProtectVirtualMemory), H(NtQueryVirtualMemory), H(NtMapViewOfSection), H(NtMapViewOfSectionEx),
   H(NtUnmapViewOfSection), H(NtUnmapViewOfSectionEx),
   /* Zw* are the same functions in ntdll */
   { "ZwAllocateVirtualMemory", (void *)hNtAllocateVirtualMemory, 0 },
   { "ZwAllocateVirtualMemoryEx", (void *)hNtAllocateVirtualMemoryEx, 0 },
   { "ZwFreeVirtualMemory", (void *)hNtFreeVirtualMemory, 0 },
   { "ZwProtectVirtualMemory", (void *)hNtProtectVirtualMemory, 0 },
   { "ZwQueryVirtualMemory", (void *)hNtQueryVirtualMemory, 0 },
   { "ZwMapViewOfSection", (void *)hNtMapViewOfSection, 0 },
   { "ZwMapViewOfSectionEx", (void *)hNtMapViewOfSectionEx, 0 },
   { "ZwUnmapViewOfSection", (void *)hNtUnmapViewOfSection, 0 },
   { "ZwUnmapViewOfSectionEx", (void *)hNtUnmapViewOfSectionEx, 0 },
   H(GlobalMemoryStatusEx), H(GetPhysicallyInstalledSystemMemory),
   H(GetProcAddress), H(LoadLibraryA), H(LoadLibraryW), H(LoadLibraryExA), H(LoadLibraryExW),
};
#define NHOOKS (sizeof(g_hooks) / sizeof(g_hooks[0]))

static struct hook *find_hook(const char *n)
{
   for (unsigned i = 0; i < NHOOKS; i++)
      if (streq(g_hooks[i].name, n) && (!g_hooks[i].orig || *g_hooks[i].orig))
         return &g_hooks[i];
   return 0;
}

static void *WINAPI hGetProcAddress(HMODULE m, const char *n)
{
   void *r = oGetProcAddress(m, n);
   if (r && (u64)n >= 0x10000) {
      struct hook *h = find_hook(n);
      if (h) { logf("GetProcAddress(%s) -> hook", n); return h->fn; }
   }
   return r;
}
static HMODULE WINAPI hLoadLibraryA(const char *n) { HMODULE r = oLoadLibraryA(n); if (r) patch_all(); return r; }
static HMODULE WINAPI hLoadLibraryW(const WCHAR *n) { HMODULE r = oLoadLibraryW(n); if (r) patch_all(); return r; }
static HMODULE WINAPI hLoadLibraryExA(const char *n, HANDLE f, u32 fl) { HMODULE r = oLoadLibraryExA(n, f, fl); if (r) patch_all(); return r; }
static HMODULE WINAPI hLoadLibraryExW(const WCHAR *n, HANDLE f, u32 fl) { HMODULE r = oLoadLibraryExW(n, f, fl); if (r) patch_all(); return r; }

/* ------------------------------------------------------ IAT patch */
static HMODULE g_self;
static HMODULE g_done[1024];
static unsigned g_ndone;

static void patch_module(HMODULE mod)
{
   u8 *base = (u8 *)mod;
   if (*(u16 *)base != 0x5a4d) return;
   u32 lfanew = *(u32 *)(base + 0x3c);
   if (lfanew > 0x1000) return;
   u8 *nt = base + lfanew;
   if (*(u32 *)nt != 0x4550 || *(u16 *)(nt + 0x18) != 0x20b) return;  /* PE32+ */
   /* Every RVA is checked against SizeOfImage: packed / protected modules (TLOU Part II's
    * steam_api64.dll) have import data the loader never uses, and walking it faulted. */
   u32 size = *(u32 *)(nt + 0x18 + 0x38);       /* SizeOfImage */
   u32 imp_rva = *(u32 *)(nt + 0x18 + 0x78);   /* DataDirectory[1] */
   u32 imp_size = *(u32 *)(nt + 0x18 + 0x7c);
   if (!imp_rva || imp_rva >= size || imp_size > size - imp_rva) return;
   int count = 0, ndesc = 0;
   for (u32 *d = (u32 *)(base + imp_rva); (u8 *)(d + 5) <= base + size && d[3] && ndesc < 512; d += 5, ndesc++) {
      u32 oft = d[0] ? d[0] : d[4];
      if (!oft || oft >= size || !d[4] || d[4] >= size || d[3] >= size) continue;
      u64 *names = (u64 *)(base + oft);
      void **iat = (void **)(base + d[4]);
      for (int i = 0; (u8 *)(names + i + 1) <= base + size && names[i] && i < 4096; i++) {
         if (names[i] >> 63) continue;                 /* by ordinal */
         if ((u32)names[i] + 2 >= size || (names[i] >> 32)) continue;
         if ((u8 *)(iat + i + 1) > base + size) break;
         const char *fn = (const char *)(base + (u32)names[i] + 2);
         struct hook *h = find_hook(fn);
         if (!h || iat[i] == h->fn) continue;
         u32 old;
         if (oVirtualProtect(&iat[i], 8, 0x04, &old)) {
            iat[i] = h->fn;
            oVirtualProtect(&iat[i], 8, old, &old);
            count++;
         }
      }
   }
   WCHAR path[260]; path[0] = 0;
   GetModuleFileNameW(mod, path, 260);
   logf("patched %d imports in %S (base %x)", (u64)count, path, (u64)mod);
}

static void patch_all(void)
{
   /* static: 8 KB on the stack would skip the guard page (built without stack probes) */
   static HMODULE mods[1024]; u32 need = 0;
   if (g_cs_ok) EnterCriticalSection(g_cs);
   if (!K32EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &need)) { if (g_cs_ok) LeaveCriticalSection(g_cs); return; }
   unsigned n = need / sizeof(HMODULE);
   if (n > 1024) n = 1024;
   for (unsigned i = 0; i < n; i++) {
      HMODULE m = mods[i];
      int seen = 0;
      for (unsigned j = 0; j < g_ndone; j++) if (g_done[j] == m) { seen = 1; break; }
      if (seen || m == g_self) continue;
      if (g_ndone < 1024) g_done[g_ndone++] = m;
      WCHAR path[260]; path[0] = 0;
      GetModuleFileNameW(m, path, 260);
      if (wcontains_ci(path, "\\windows\\")) continue;  /* system / Wine modules */
      patch_module(m);
   }
   if (g_cs_ok) LeaveCriticalSection(g_cs);
}



static void dump_bytes(const char *tag, u64 a, int before, int after)
{
   char line[200]; int n;
   for (int off = -before; off < after; off += 32) {
      n = 0;
      const char *t = tag; while (*t && n < 40) line[n++] = *t++;
      line[n++] = ' ';
      u64 x = a + off; char hx[17]; int i = 16; hx[16] = 0;
      while (i--) { int d = x & 15; hx[i] = d < 10 ? '0' + d : 'a' + d - 10; x >>= 4; }
      for (i = 0; i < 16; i++) line[n++] = hx[i];
      line[n++] = ':';
      for (int b = 0; b < 32; b++) {
         u8 v = ((u8 *)(a + off))[b];
         line[n++] = ' '; line[n++] = "0123456789abcdef"[v >> 4]; line[n++] = "0123456789abcdef"[v & 15];
      }
      line[n] = 0;
      logf("%s", line);
   }
}

static u64 g_trap;            /* address of the patched int 0x29 */
static u64 g_ncpy;           /* strncpy_s entry trap (0x140ff1648) */
static unsigned g_ncpy_calls, g_ncpy_logged;
static char g_setname[64] = "ingame";   /* TLOUHOOK_SETNAME: name for the unnamed PSO library */
static int g_setfix = 1;                  /* TLOUHOOK_SETFIX=0 disables the substitution */
static void install_fastfail_trap(void)
{
   u64 target = 0x140fd46c8ull;
   char buf[32];
   if (GetEnvironmentVariableA("TLOUHOOK_FASTFAIL", buf, sizeof(buf)) && buf[0]) target = parse_hex(buf);
   dump_bytes("code", target, 64, 64);
   for (u64 a = target - 16; a < target + 16; a++) {
      u8 *p = (u8 *)a;
      if (p[0] == 0xcd && p[1] == 0x29) {
         u32 old;
         if (oVirtualProtect(p, 2, 0x40, &old)) {
            p[0] = 0xcc; p[1] = 0x90;          /* int3; nop */
            oVirtualProtect(p, 2, old, &old);
            g_trap = a;
            logf("fastfail trap at %x (int 0x29 -> int3)", a);
            return;
         }
      }
   }
   logf("no int 0x29 found near %x", target);
}

/* Entry of the game's strncpy_s (the call that fails). "48 89 5c 24 08" = mov [rsp+8],rbx is
 * replaced by int3; the handler logs suspicious calls and emulates the instruction. */
static void install_strncpy_trap(void)
{
   u64 a = 0x140ff1648ull;
   char buf[32];
   if (GetEnvironmentVariableA("TLOUHOOK_STRNCPY", buf, sizeof(buf)) && buf[0]) a = parse_hex(buf);
   u8 *p = (u8 *)a;
   if (!(p[0] == 0x48 && p[1] == 0x89 && p[2] == 0x5c && p[3] == 0x24 && p[4] == 0x08)) {
      logf("strncpy_s entry not recognised at %x", a);
      dump_bytes("entry", a, 0, 32);
      return;
   }
   u32 old;
   if (oVirtualProtect(p, 1, 0x40, &old)) { p[0] = 0xcc; oVirtualProtect(p, 1, old, &old); g_ncpy = a; logf("strncpy_s trap at %x", a); }
}

static int safe_str(u64 a) { return a > 0x10000 && a < 0x800000000000ull; }


static void log_addr(const char *what, u64 a);
/* ---------------------------------------------- x64 stack unwinder
 * Walks the exact call chain with the modules' .pdata (UNWIND_INFO), so the
 * frames are real return addresses, not stack-scan guesses. */
static int find_rf(u64 pc, u64 *base_out, u32 **rf_out)
{
   HMODULE m = 0;
   if (!GetModuleHandleExW(4 | 2, (const void *)pc, &m) || !m) return 0;
   u8 *base = (u8 *)m;
   u8 *nt = base + *(u32 *)(base + 0x3c);
   if (*(u16 *)(nt + 0x18) != 0x20b) return 0;
   u32 rva = *(u32 *)(nt + 0x18 + 0x70 + 3 * 8), size = *(u32 *)(nt + 0x18 + 0x70 + 3 * 8 + 4);  /* exception dir */
   if (!rva) return 0;
   u32 *rf = (u32 *)(base + rva); u32 n = size / 12; u32 off = (u32)(pc - (u64)base);
   u32 lo = 0, hi = n;
   while (lo < hi) {
      u32 mid = (lo + hi) / 2; u32 *e = rf + mid * 3;
      if (off < e[0]) hi = mid; else if (off >= e[1]) lo = mid + 1;
      else { *base_out = (u64)base; *rf_out = e; return 1; }
   }
   return 0;
}

/* regs[16] = rax..r15, returns new rip or 0 */
static u64 unwind_once(u64 pc, u64 *regs)
{
   u64 base; u32 *rf;
   if (!find_rf(pc, &base, &rf)) {            /* leaf: return address at [rsp] */
      u64 ra = *(u64 *)regs[4]; regs[4] += 8; return ra;
   }
   u32 off = (u32)(pc - base) - rf[0];
   u8 *ui = (u8 *)(base + rf[2]);
   for (int chain = 0; chain < 8; chain++) {
      u8 flags = ui[0] >> 3, prolog = ui[1], count = ui[2], fr = ui[3] & 15;
      u16 *codes = (u16 *)(ui + 4);
      u64 frame_base = regs[4];
      if (fr) frame_base = regs[fr] - (u64)(ui[3] >> 4) * 16;
      int i = 0;
      while (i < count) {
         u8 codeoff = codes[i] & 0xff, op = (codes[i] >> 8) & 15, info = codes[i] >> 12;
         int skip = chain == 0 && codeoff > off;   /* not executed yet */
         switch (op) {
         case 0: if (!skip) { regs[info] = *(u64 *)regs[4]; regs[4] += 8; } i += 1; break;  /* PUSH_NONVOL */
         case 1: { u64 sz; int n2;
                   if (info == 0) { sz = (u64)codes[i + 1] * 8; n2 = 2; } else { sz = codes[i + 1] | ((u64)codes[i + 2] << 16); n2 = 3; }
                   if (!skip) regs[4] += sz; i += n2; break; }                          /* ALLOC_LARGE */
         case 2: if (!skip) regs[4] += (u64)info * 8 + 8; i += 1; break;                  /* ALLOC_SMALL */
         case 3: if (!skip) regs[4] = frame_base; i += 1; break;                         /* SET_FPREG */
         case 4: if (!skip) regs[info] = *(u64 *)(frame_base + (u64)codes[i + 1] * 8); i += 2; break;
         case 5: if (!skip) regs[info] = *(u64 *)(frame_base + (codes[i + 1] | ((u64)codes[i + 2] << 16))); i += 3; break;
         case 8: i += 2; break;                                                          /* SAVE_XMM128 */
         case 9: i += 3; break;
         case 10: if (!skip) { u64 sp = regs[4] + (info ? 8 : 0); u64 ra = *(u64 *)sp; regs[4] = *(u64 *)(sp + 24); return ra; } i += 1; break;
         default: i += 1; break;
         }
      }
      if (!(flags & 4)) break;                    /* chained info */
      u32 *ch = (u32 *)(codes + ((count + 1) & ~1));
      ui = (u8 *)(base + ch[2]);
      off = 0xffffffff;                           /* chained parts are fully executed */
   }
   u64 ra = *(u64 *)regs[4]; regs[4] += 8;
   return ra;
}

static void log_backtrace(u8 *ctx)
{
   u64 regs[16];
   for (int i = 0; i < 16; i++) regs[i] = *(u64 *)(ctx + 0x78 + 8 * i);
   u64 pc = *(u64 *)(ctx + 0xf8);
   logf("  backtrace (unwound):");
   for (int f = 0; f < 40 && pc > 0x10000; f++) {
      log_addr(f ? "frame" : "pc", pc);
      if (pc < 0x140000000ull || pc >= 0x150000000ull) break;   /* only the game's x64 frames */
      if (f && f <= 14) dump_bytes("    before", pc - 64, 0, 64);
      u64 next = unwind_once(pc, regs);
      if (next == pc || regs[4] < 0x10000) break;
      pc = next;
   }
}

/* ------------------------------------------------ exception logger
 * Logs exceptions the game raises (first chance): C++ exception type names,
 * debug strings (HALT / assert texts), access violations, with the module and
 * offset of the faulting code and return addresses found on the stack. */
struct exrec { u32 code; u32 flags; void *rec; void *addr; u32 n; u32 pad; u64 info[15]; };
struct exptrs { struct exrec *rec; u8 *ctx; };
static unsigned g_nexc;

static void mod_of(u64 a, const WCHAR **name, u64 *off)
{
   static WCHAR buf[8][260]; static unsigned slot;
   HMODULE m = 0;
   *name = 0; *off = a;
   if (a > 0x10000 && GetModuleHandleExW(4 | 2, (const void *)a, &m) && m) {
      WCHAR *b = buf[slot++ & 7]; b[0] = 0;
      GetModuleFileNameW(m, b, 260);
      const WCHAR *s = b; for (const WCHAR *q = b; *q; q++) if (*q == '\\') s = q + 1;
      *name = s; *off = a - (u64)m;
   }
}

static void log_addr(const char *what, u64 a)
{
   const WCHAR *n; u64 off; mod_of(a, &n, &off);
   if (n) logf("  %s %x = %S+%x", what, a, n, off); else logf("  %s %x", what, a);
}

static long WINAPI veh(struct exptrs *ep)
{
   struct exrec *r = ep->rec;
   u32 c = r->code;
   if (c == 0x406d1388) return 0;                      /* thread name */
   /* Fix-ups for what the unnamed "sets" PSO library and the absent Razer Chroma DLL leave NULL. */
   if (c == 0xc0000005 && ep->ctx && g_setfix) {
      u8 *x = ep->ctx;
      u64 *rip = (u64 *)(x + 0xf8), *rsp = (u64 *)(x + 0x98), *rax = (u64 *)(x + 0x78);
      u64 rdx = *(u64 *)(x + 0x88);
      static unsigned nfix;
      if (*rip == 0x1411187e4ull && rdx == 0) {           /* PSO request stats: no source -> return */
         *rip = 0x141118841ull;
         if (nfix++ < 20) logf("fix: PSO request without a source at 0x1411187e4 -> skipped");
         return -1;
      }
      if (*rip == 0 && *rsp > 0x10000) {                    /* call through a NULL plugin pointer */
         u64 ret = *(u64 *)*rsp;
         if (ret >= 0x140000000ull && ret < 0x150000000ull) {
            *rax = 0; *rip = ret; *rsp += 8;
            if (nfix++ < 40) { logf("fix: call to NULL function pointer, returning 0"); log_addr("  from", ret); }
            return -1;
         }
      }
   }
   if (c == 0x80000003 && g_ncpy && (u64)r->addr == g_ncpy && ep->ctx) {
      u8 *x = ep->ctx;
      u64 *rsp = (u64 *)(x + 0x98), *rip = (u64 *)(x + 0xf8);
      u64 rcx = *(u64 *)(x + 0x80), rdx = *(u64 *)(x + 0x88), r8 = *(u64 *)(x + 0xb8), r9 = *(u64 *)(x + 0xc0);
      u64 rbx = *(u64 *)(x + 0x90);
      g_ncpy_calls++;
      /* The 3rd PsoPreLoading library ("sets", psolibs/main/sets) takes its name from the
         source table slot 1 (0x14642cbf8), which nothing filled: give it one. */
      if (g_setfix && !r8 && *(u64 *)*rsp == 0x1421e0c1full) {
         *(u64 *)(x + 0xb8) = (u64)g_setname;
         if (!*(u64 *)0x14642cbf8ull) *(u64 *)0x14642cbf8ull = (u64)g_setname;
         logf("strncpy_s#%d: NULL library name -> \"%s\" (slot 1 filled)", (u64)g_ncpy_calls, g_setname);
         r8 = (u64)g_setname;
      }
      if ((!rcx || !r8 || g_ncpy_logged < 40) && g_ncpy_logged < 200) {
         g_ncpy_logged++;
         logf("strncpy_s#%d dst %x size %x src %x count %x caller %x", (u64)g_ncpy_calls, rcx, rdx, r8, r9, *(u64 *)*rsp);
         if (safe_str(r8)) { char t[96]; int i = 0; for (; i < 95 && ((char *)r8)[i]; i++) t[i] = ((char *)r8)[i]; t[i] = 0; logf("  src \"%s\"", t); }
         if (!rcx || !r8) {
            static const char *nm[16] = {"rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi","r8","r9","r10","r11","r12","r13","r14","r15"};
            for (int i = 0; i < 16; i++) logf("  %s %x", nm[i], *(u64 *)(x + 0x78 + 8 * i));
            u64 obj = *(u64 *)(x + 0x78 + 8 * 7);   /* rdi in the constructor */
            if (safe_str(obj)) dump_bytes("  [rdi]", obj, 0, 0x100);
            u64 obj2 = *(u64 *)(x + 0x78 + 8 * 6);  /* rsi */
            if (safe_str(obj2)) dump_bytes("  [rsi]", obj2, 0, 0x80);
            /* the pak-group config the constructor reads (r15 / caller's r8) and the static
               names around "fg-paks" / "bg-paks" */
            dump_bytes("  cfg", 0x14642b600ull, 0, 0x600);
            dump_bytes("  names", 0x142e33300ull, 0, 0x200);
            for (u64 a = 0x14642b600ull; a < 0x14642bc00ull; a += 8) {
               u64 v = *(u64 *)a;
               if (v >= 0x140000000ull && v < 0x150000000ull) {
                  const char *t = (const char *)v; int ok = 1, n = 0;
                  for (; n < 48 && t[n]; n++) if (t[n] < 32 || t[n] > 126) { ok = 0; break; }
                  if (ok && n >= 3) { char b[64]; for (int i = 0; i < n; i++) b[i] = t[i]; b[n] = 0; logf("  cfg+%x -> %x \"%s\"", a - 0x14642b600ull, v, b); }
               }
            }
            log_backtrace(x);
         }
      }
      *(u64 *)(*rsp + 8) = rbx;            /* emulate mov [rsp+8], rbx */
      *rip = g_ncpy + 5;
      return -1;                            /* EXCEPTION_CONTINUE_EXECUTION */
   }
   if (g_nexc > 600) return 0;
   if (c == 0x40010006 || c == 0x4001000a) {          /* OutputDebugString */
      if (r->n >= 2 && r->info[1]) {
         g_nexc++;
         if (c == 0x40010006) logf("debug: %s", (const char *)r->info[1]);
         else logf("debug: %S", (const WCHAR *)r->info[1]);
      }
      return 0;
   }
   g_nexc++;
   logf("EXCEPTION %x flags %x params %d", (u64)c, (u64)r->flags, (u64)r->n);
   log_addr("at", (u64)r->addr);
   if (c == 0xc0000005 && r->n >= 2) logf("  access %d to %x", r->info[0], r->info[1]);
   if (c == 0xe06d7363 && r->n >= 4 && r->info[2] && r->info[3]) {   /* MSVC C++ throw */
      u64 base = r->info[3];
      u32 *ti = (u32 *)r->info[2];
      int *cta = (int *)(base + (u32)ti[3]);
      for (int i = 0; i < cta[0] && i < 4; i++) {
         u32 *ct = (u32 *)(base + (u32)cta[1 + i]);
         const char *tname = (const char *)(base + ct[1] + 16);
         logf("  C++ type %s", tname);
      }
      /* what(): std::exception::_What at +8; nlohmann keeps it in its runtime_error member (+40) */
      u64 obj = r->info[1];
      if (safe_str(obj)) {
         for (int k = 0; k < 2; k++) {
            u64 w = *(u64 *)(obj + (k ? 40 : 8));
            if (safe_str(w)) { char t[200]; int i = 0; for (; i < 199 && ((char *)w)[i]; i++) t[i] = ((char *)w)[i]; t[i] = 0; if (i) logf("  what: %s", t); }
         }
      }
      /* who threw: return addresses into the game found on the stack (RaiseException is native
         ARM64EC code, the x64 unwinder can't walk through it) */
      static int nbt;
      if (ep->ctx && nbt++ < 3) {
         u64 rsp = *(u64 *)(ep->ctx + 0x98); int shown = 0;
         if (rsp > 0x10000 && rsp < 0x800000000000ull)
            for (int i = 0; i < 2000 && shown < 10; i++) {
               u64 v = ((u64 *)rsp)[i];
               if (v >= 0x140000000ull && v < 0x150000000ull) { log_addr("thrown via?", v); shown++; }
            }
      }
   }
   if (ep->ctx && c != 0xe06d7363) {
      u64 rsp = *(u64 *)(ep->ctx + 0x98), rip = *(u64 *)(ep->ctx + 0xf8), rcx = *(u64 *)(ep->ctx + 0x80);
      int trap = g_trap && c == 0x80000003 && (u64)r->addr >= g_trap && (u64)r->addr <= g_trap + 2;
      logf("  rip %x rsp %x rcx %x", rip, rsp, rcx);
      if (trap) {
         logf("*** FASTFAIL code %d (rcx); registers:", rcx);
         static const char *nm[16] = {"rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi","r8","r9","r10","r11","r12","r13","r14","r15"};
         for (int i = 0; i < 16; i++) logf("  %s %x", nm[i], *(u64 *)(ep->ctx + 0x78 + 8 * i));
         log_backtrace(ep->ctx);
         return 0;
      }
      if (rsp > 0x10000 && rsp < 0x800000000000ull) {
         int shown = 0, limit = trap ? 24 : 12;
         for (int i = 0; i < (trap ? 4000 : 400) && shown < limit; i++) {
            u64 v = ((u64 *)rsp)[i];
            if (v >= 0x140000000ull && v < 0x150000000ull) {
               log_addr("stack ret?", v); shown++;
               if (trap && shown <= 10) dump_bytes("  caller", v, 96, 32);
            }
         }
      }
   }
   return 0;   /* EXCEPTION_CONTINUE_SEARCH */
}

/* ------------------------------------------------------- dxgi exports
 * Real functions, not export forwarders: NVIDIA Streamline (TLOU Part II's sl.interposer)
 * walks dxgi.dll's export table itself and jumped to the forwarder STRING
 * ("dxgi_dxvk.CreateDXGIFactory2") as if it were code. Each export loads DXVK's dxgi
 * (dxgi_dxvk.dll next to this one) once and passes the call on; x64 passes the first four
 * arguments in registers, so four u64s carry any of these signatures. */
IMP HMODULE WINAPI LoadLibraryW(const WCHAR *);
typedef u64 (*pfx)(u64, u64, u64, u64);
static HMODULE g_real;
static void *real_fn(const char *name)
{
   if (!g_real) {
      WCHAR path[300]; u32 n = GetModuleFileNameW(g_self, path, 280);
      while (n && path[n - 1] != '\\') n--;
      const char *f = "dxgi_dxvk.dll";
      for (int i = 0; f[i]; i++) path[n++] = f[i];
      path[n] = 0;
      g_real = LoadLibraryW(path);
      if (!g_real) { logf("dxgi_dxvk.dll not found next to the hook"); return 0; }
   }
   return GetProcAddress(g_real, name);
}
#define FWD(name) \
   __declspec(dllexport) u64 name(u64 a, u64 b, u64 c, u64 d) { \
      static pfx f; if (!f) f = (pfx)real_fn(#name); \
      return f ? f(a, b, c, d) : 0x80004001ull /* E_NOTIMPL */; }
FWD(CreateDXGIFactory)
FWD(CreateDXGIFactory1)
FWD(CreateDXGIFactory2)
FWD(DXGIDeclareAdapterRemovalSupport)
FWD(DXGIDisableVBlankVirtualization)
FWD(DXGIGetDebugInterface)
FWD(DXGIGetDebugInterface1)
FWD(DXGIReportAdapterConfiguration)

/* ---------------------------------------------------------- init */
static void open_log(HMODULE self)
{
   WCHAR path[300]; u32 n = GetModuleFileNameW(self, path, 280);
   while (n && path[n - 1] != '\\') n--;
   const char *name = "tlouhook.log";
   for (int i = 0; name[i]; i++) path[n++] = name[i];
   path[n] = 0;
   /* GENERIC_WRITE, share read|write, CREATE_ALWAYS, NORMAL */
   g_log = CreateFileW(path, 0x40000000, 3, 0, 2, 0x80, 0);
}

BOOL WINAPI DllMain(HMODULE inst, u32 reason, void *res)
{
   (void)res;
   if (reason != 1) return 1;
   g_self = inst;
   DisableThreadLibraryCalls(inst);
   InitializeCriticalSection(g_cs); g_cs_ok = 1;
   open_log(inst);

   char buf[64];
   if (GetEnvironmentVariableA("AZHOOK_BASE", buf, sizeof(buf)) && buf[0]) LO_BASE = parse_hex(buf);
   if (GetEnvironmentVariableA("TLOUHOOK_SETNAME", buf, sizeof(buf)) && buf[0]) { int i = 0; for (; i < 63 && buf[i]; i++) g_setname[i] = buf[i]; g_setname[i] = 0; }
   if (GetEnvironmentVariableA("TLOUHOOK_SETFIX", buf, sizeof(buf)) && buf[0] == '0') g_setfix = 0;
   if (GetEnvironmentVariableA("TLOUHOOK_RAM_GB", buf, sizeof(buf)) && buf[0]) {
      u64 g = 0; for (char *c = buf; *c >= '0' && *c <= '9'; c++) g = g * 10 + (*c - '0');
      g_fake_ram = g << 30;
      logf("reporting %d GB of RAM", g);
   }
   logf("azhook loaded, window %x-%x -> %x", HI_BASE, HI_END, LO_BASE);

   HMODULE nt = GetModuleHandleA("ntdll.dll");
   HMODULE kb = GetModuleHandleA("kernelbase.dll");
   HMODULE k32 = GetModuleHandleA("kernel32.dll");
   for (unsigned i = 0; i < NHOOKS; i++) {
      if (!g_hooks[i].orig) continue;
      const char *n = g_hooks[i].name;
      void *p = 0;
      if (n[0] == 'N' && n[1] == 't') p = nt ? GetProcAddress(nt, n) : 0;
      else {
         if (k32) p = GetProcAddress(k32, n);
         if (!p && kb) p = GetProcAddress(kb, n);
      }
      *g_hooks[i].orig = p;
      if (!p) logf("  no original for %s (not hooked)", n);
   }
   AddVectoredExceptionHandler(1, (void *)veh);
   logf("exception logger installed");
   if (!oVirtualProtect) { logf("no VirtualProtect, giving up"); return 1; }
   patch_all();
   /* The traps below patch fixed addresses of The Last of Us Part I's exe: only there
    * (Part II and other games get the address-space part only). */
   WCHAR exe[300]; exe[0] = 0;
   GetModuleFileNameW(0, exe, 300);
   if (wcontains_ci(exe, "\\tlou-i.exe") || wcontains_ci(exe, "\\tlou-i-l.exe")) {
      install_fastfail_trap();
      install_strncpy_trap();
   } else {
      logf("not Part I (%S): no Part I traps", exe);
   }
   return 1;
}
