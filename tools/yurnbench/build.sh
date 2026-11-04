#!/bin/sh
# Builds YurnBench (x86_64 Windows) into the given path. Needs mingw-w64 (apt: mingw-w64).
set -e
out="${1:-yurnbench.exe}"
mkdir -p "$(dirname "$out")"
x86_64-w64-mingw32-gcc -O2 -Wall -mwindows -static -s "$(dirname "$0")/bench.c" -o "$out" \
  -ld3d12 -ld3d11 -ldxgi -ld3dcompiler_47 -luuid
echo "built $out ($(stat -c %s "$out") bytes)"
