# YurNLator

Windows games and apps on Android (Wine + Box64 / FEXCore), with one clean interface,
automatic Adreno / Mali setup and package-name variants.

Based on [Winlator Ludashi 4.1](https://github.com/StevenMXZ/Winlator-Ludashi) by StevenMXZ,
itself a fork of Winlator Bionic by Pipetto-crypto and the original Winlator by BrunoSX.

## Planned
- GPU profile on first launch (Adreno / Mali / other), changeable later with a warning
- Driver & wrapper manager, drivers from repositories, readable driver names with a short note
- ReShade, LSFG, alternative display backend
- Mali support ported from [Winlator Mali](https://github.com/GunaCharanTeja/WinlatorMali)

## Variants
Each variant is the same app under a different package name and installs separately
(own containers and data).

| Variant | Package |
|---|---|
| standard | `com.yurnayt.yurnlator` |
| antutu | `com.antutu.ABenchMark` |
| antutuAlt | `com.antutu.benchmark.full` |
| ludashi | `com.ludashi.benchmark` |
| ludashi2 | `com.ludashi.benchmark2` |
| genshin | `com.miHoYo.GenshinImpact` |
| pubg | `com.tencent.ig` (also works for Red Magic game mode) |
| geekbench6 | `com.primatelabs.geekbench6` |
| vivo | `com.netease.moba.vivo` (vivo / iQOO built-in frame interpolation) |

## Building
APKs are built by GitHub Actions: every push builds `standard`; pushing a tag `v*`
builds every variant and publishes a release. Locally: `./gradlew assembleStandardRelease`.

## Credits
BrunoSX (Winlator), Pipetto-crypto (Winlator Bionic), StevenMXZ (Winlator Ludashi),
GunaCharanTeja (Winlator Mali), The412Banner (Bannerlator), ptitSeb (Box64), FEX-Emu, Mesa, DXVK, VKD3D-Proton.
