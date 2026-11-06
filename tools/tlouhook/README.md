# tlouhook (The Last of Us Part I)

A proxy `dxgi.dll` that the app puts in the game folder (session/GameFixes): its exports load and call
`dxgi_dxvk.dll` (real functions, not forwarders: Streamline walks the export table itself) (a copy of the container's DXVK dxgi.dll), and on load it

- moves the game's fixed reservations at 0x600'0000'0000 into Android's 39-bit address space,
- clamps MEM_ADDRESS_REQUIREMENTS.HighestEndingAddress (the game asks for 40 bit; Wine refuses),
- fills the NULL pipeline-library name the game's strncpy_s gets,
- logs exceptions to `tlouhook.log` next to the game.

`app/src/main/assets/gamefixes/tlou_dxgi.dll` is built from `hook.c` + `dxgi.def` with
x86_64-w64-mingw32 (freestanding: -nostdlib, kernel32 only).
