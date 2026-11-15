package com.winlator.cmod.core

/**
 * Which graphics API the game uses and through which layer, from what DXVK / VKD3D-Proton
 * print at start (the window's engine property often doesn't say; for DX12 DXVK only lists
 * adapters and its own device lines never come):
 *   vkd3d-proton lines / d3d12.dll loaded   -> "VKD3D 3.1 · DX12"
 *   DXVK "D3D11 … device" / feature level    -> "DXVK 3.1.1 · DX11"
 *   DXVK D3D9 / D3D8 device                  -> "DXVK 3.1.1 · DX9" / "DX8"
 * Versions: DXVK's from its banner ("info:  DXVK: v3.1.1-…"), VKD3D's from the launch settings.
 *
 * DX12 seen in a launcher's browser (Ubisoft Connect's, Steam's, Epic's: Chromium's GPU process
 * tries D3D12) doesn't count: vkd3d-proton's lines carry the process id, and Wine names each
 * process at start ("<main thread id>:err:environ:init_peb starting L"C:\…\x.exe"", the main
 * thread's id being the process id + 4).
 */
class ApiSniffer(dxwrapperConfig: String?, private val onFound: (String) -> Unit) : Callback<String> {
    private val vkd3dConfigured: String = short(Regex("(?:^|,)vkd3dVersion=([^,]*)").find(dxwrapperConfig.orEmpty())?.groupValues?.get(1))
    private var dxvk: String = short(Regex("(?:^|,)version=([^,]*)").find(dxwrapperConfig.orEmpty())?.groupValues?.get(1))
    private var api: String? = null
    private var last: String? = null
    /** Process id (hex, as Wine prints it) -> exe name, lower case. */
    private val processes = HashMap<String, String>()

    override fun call(line: String?) {
        if (line == null || line.length > 400) return
        Regex("""^info:\s+DXVK:\s+v?([\w.\-]+)""").find(line.trim())?.let { dxvk = short(it.groupValues[1]) }
        if (line.contains(":init_peb starting")) {
            INIT_PEB.find(line)?.let { m ->
                val pid = (m.groupValues[1].toIntOrNull(16) ?: return) - 4
                processes[String.format("%04x", pid)] = m.groupValues[2].substringAfterLast('\\').lowercase()
            }
            return
        }
        val found = when {
            line.contains("vkd3d-proton") || line.contains("d3d12core.dll") || line.contains("D3D12CreateDevice") ->
                if (fromHelper(line)) null else "DX12"
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

    /** A vkd3d-proton line ("ts:pid:tid:warn:vkd3d-proton:…") from a launcher's browser process. */
    private fun fromHelper(line: String): Boolean {
        val pid = VKD3D_PID.find(line)?.groupValues?.get(1)?.lowercase() ?: return false
        val exe = processes[pid] ?: return false
        return exe in HELPERS
    }

    companion object {
        private val INIT_PEB = Regex("""^([0-9a-fA-F]{4,8}):err:environ:init_peb starting L"([^"]+)"""")
        private val VKD3D_PID = Regex("""^[\d.]+:([0-9a-fA-F]{4,8}):[0-9a-fA-F]{4,8}:""")
        /** Launchers and their web helpers: they create D3D12 devices to probe the GPU. */
        private val HELPERS = setOf(
            "uplaywebcore.exe", "upc.exe", "ubisoftconnect.exe", "uplay.exe", "ubisoftgamelauncher.exe",
            "steamwebhelper.exe", "steam.exe", "epicwebhelper.exe", "epicgameslauncher.exe",
            "launcherpatcher.exe", "rockstarservice.exe", "socialclubhelper.exe", "eadesktop.exe",
            "qtwebengineprocess.exe", "msedgewebview2.exe", "cefsharp.browsersubprocess.exe",
        )

        /** "v3.1.1-1-gplasync-arm64ec" -> "3.1.1", "3.1-arm64ec" -> "3.1". */
        fun short(v: String?): String = Regex("""(\d+(?:\.\d+){0,2})""").find(v.orEmpty())?.groupValues?.get(1).orEmpty()
    }
}
