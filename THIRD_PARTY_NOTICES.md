# Third-party notices

YurNLator is distributed under the GNU General Public License v3.0 (see `LICENSE`).

It contains code from these projects, under their own licenses:

| Project | License | What |
|---|---|---|
| [Winlator](https://github.com/brunodev85/winlator) by BrunoSX, Winlator Bionic, [Winlator Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) | MIT (`LICENSE-WINLATOR-MIT`) | the app base |
| [Bannerlator](https://github.com/The412Banner/Bannerlator) by The412Banner | GPL-3.0 | Wayland compositor (`cpp/waylandcomp`), LSFG Native, Win-FG engine, wrapper / driver catalogs |
| [Winlator Mali](https://github.com/GunaCharanTeja/WinlatorMali) | per upstream | Mali wrappers, BCn layer (leegao, modified by WinMali-Dev) |
| [libwayland](https://gitlab.freedesktop.org/wayland/wayland) | MIT | Wayland server library (prebuilt, bionic) |
| [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk), Eden Emulator Project | GPL-3.0-or-later | LSFG frame generation (needs the user's own Lossless.dll, not bundled) |

The MIT notices of the Winlator base are kept as required by that license.

## LSFG frame generation (app/src/main/cpp/winlator/lsfg)
Native Lossless Scaling frame-generation chain, ported from Bannerlator (GPL-3.0), which ported it
from WinNative's vkr_lsfg.cpp (GPL-3.0-or-later); LSFG port credited to Camille LaVey / the Eden
Emulator Project, following upstream lsfg-vk. No Lossless Scaling code or shaders are shipped:
the shaders are read from the user's own Lossless.dll on the device.

## DXVK DXBC translator (app/src/main/cpp/thirdparty/dxbc)
zlib licence, (c) Philip Rebohle, Joshua Ashton, Robin Kertels, Jeffrey Ellison and contributors.
See thirdparty/dxbc/LICENSE.md.

## WFG frame generation (app/src/main/cpp/wfg)
WFG — WinNative/Winlator Frame Generator, (c) 2026 qwertypower (DEVAR Entertainment LLC), MIT
(wfg/LICENSE), from WinNative (github.com/WinNative-Emu/WinNative). A Vulkan compute realisation
of Dense Inverse Search optical flow; algorithm and reference from OpenCV's DISOpticalFlow
(Till Kroeger's OF_DIS). vkshim/vk_dispatch.h and bin2c.cmake are from WinNative (GPL-3.0).

## Windows components installer (app/src/main/java/com/winlator/cmod/components)
Component catalog reader, offline installer (MSI / cabinet / recorded-snapshot layout without
running the setup), undo journal, prefix detection and redistributable detection, ported from
Bannerlator (GPL-3.0). The catalog (components.json) is read from The412Banner/winlator-contents.
Codec settings (session/MediaDecoder) follow Bannerlator's MediaDecoder.

## 7-Zip (app/src/main/jniLibs/arm64-v8a/lib7zz.so)
7-Zip 24.08 console, (c) Igor Pavlov, GNU LGPL with unRAR restriction, built for Android/bionic;
taken from Bannerlator. See NOTICE_7ZIP.txt.
