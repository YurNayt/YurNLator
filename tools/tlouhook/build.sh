#!/bin/sh
# Builds the TLOU hook (proxy dxgi.dll, x86_64 Windows, freestanding) into the given path.
set -e
out="$1"; dir="$(dirname "$0")"
x86_64-w64-mingw32-gcc -O2 -shared -nostdlib -ffreestanding -fno-builtin -fno-stack-protector \
  -mno-stack-arg-probe -Wl,-e,DllMain -Wl,--enable-stdcall-fixup -s \
  -o "$out" "$dir/hook.c" "$dir/dxgi.def" -lkernel32 -lgcc
