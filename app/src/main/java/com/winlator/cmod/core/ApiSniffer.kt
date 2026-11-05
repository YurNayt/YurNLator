package com.winlator.cmod.core

/**
 * Which graphics API the game uses and through which layer, from what DXVK / VKD3D-Proton
 * print at start (the window's engine property often doesn't say; for DX12 DXVK only lists
 * adapters and its own device lines never come):
 *   vkd3d-proton lines / d3d12.dll loaded   -> "VKD3D 3.1 · DX12"
 *   DXVK "D3D11 … device" / feature level    -> "DXVK 3.1.1 · DX11"
 *   DXVK D3D9 / D3D8 device                  -> "DXVK 3.1.1 · DX9" / "DX8"
 * Versions: DXVK's from its banner ("info:  DXVK: v3.1.1-…"), VKD3D's from the launch settings.
 */
class ApiSniffer(dxwrapperConfig: String?, private val onFound: (String) -> Unit) : Callback<String> {
    private val vkd3dConfigured: String = short(Regex("(?:^|,)vkd3dVersion=([^,]*)").find(dxwrapperConfig.orEmpty())?.groupValues?.get(1))
    private var dxvk: String = short(Regex("(?:^|,)version=([^,]*)").find(dxwrapperConfig.orEmpty())?.groupValues?.get(1))
    private var api: String? = null
    private var last: String? = null

    override fun call(line: String?) {
        if (line == null || line.length > 400) return
        Regex("""^info:\s+DXVK:\s+v?([\w.\-]+)""").find(line.trim())?.let { dxvk = short(it.groupValues[1]) }
        val found = when {
            line.contains("vkd3d-proton") || line.contains("d3d12core.dll") || line.contains("D3D12CreateDevice") -> "DX12"
            api == "DX12" -> null   // DX12 wins: DXVK still prints its dxgi lines next to it
            line.contains("D3D11InternalCreateDevice") || line.contains("D3D11CoreCreateDevice") ||
                line.contains("D3D11DXGIDevice") -> "DX11"
            line.contains("D3D9DeviceEx") || line.contains("Direct3DCreate9") -> if (api == "DX8") null else "DX9"
            line.contains("D3D8Device") || line.contains("Direct3DCreate8") -> "DX8"
            line.contains("D3D10") && line.contains("Device") -> if (api == null) "DX10" else null
            else -> null
        } ?: return
        api = found
        val label = when (found) {
            "DX12" -> "VKD3D" + (if (vkd3dConfigured.isNotEmpty()) " $vkd3dConfigured" else "") + " · DX12"
            else -> "DXVK" + (if (dxvk.isNotEmpty()) " $dxvk" else "") + " · $found"
        }
        if (label != last) { last = label; onFound(label) }
    }

    companion object {
        /** "v3.1.1-1-gplasync-arm64ec" -> "3.1.1", "3.1-arm64ec" -> "3.1". */
        fun short(v: String?): String = Regex("""(\d+(?:\.\d+){0,2})""").find(v.orEmpty())?.groupValues?.get(1).orEmpty()
    }
}
