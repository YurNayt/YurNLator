package com.winlator.cmod.core

/**
 * Finds out which Vulkan driver the game really runs on, from what the driver and DXVK print
 * at start. The settings can't tell: a wrapper may load its own driver (the "Apex" Winlator
 * Mali wrapper loads PanVK through adrenotools) while "Driver Version" says System.
 *
 *   "WARNING: panvk is not a conformant Vulkan implementation"  -> PanVK
 *   "FristOneRR 3.0 (Mesa 26.3.0-devel) (git-…)"                 -> build name
 *   DXVK "Driver : Wrapper driver 49.1.0" / "Driver: 49.1.0"     -> vendor driver version
 */
class DriverSniffer(private val onFound: (String) -> Unit) : Callback<String> {
    private val nonConformant = Regex("""(\w+) is not a conformant Vulkan implementation""")
    private val mesaBuild = Regex("""^([A-Za-z][\w .\-]{1,40}?) \(Mesa (\d+\.\d+[\w.\-]*)\)""")
    private val dxvkDriver = Regex("""^info:\s+Driver\s*:?\s*(?:Wrapper driver\s*)?([\w .\-]+)$""")
    private var family: String? = null
    private var build: String? = null
    private var version: String? = null
    private var last: String? = null

    override fun call(line: String?) {
        if (line == null || line.length > 300) return
        var changed = false
        nonConformant.find(line)?.let {
            val f = when (it.groupValues[1].lowercase()) { "panvk" -> "PanVK"; "tu", "turnip" -> "Turnip"; else -> it.groupValues[1] }
            if (f != family) { family = f; changed = true }
        }
        if (build == null) mesaBuild.find(line)?.let {
            val name = it.groupValues[1].trim()
            build = if (name.equals("Mesa", true)) "Mesa ${it.groupValues[2]}" else name
            changed = true
        }
        if (version == null) dxvkDriver.find(line.trim())?.let { version = it.groupValues[1].trim(); changed = true }
        if (!changed) return
        val label = when {
            family != null && build != null -> "$family · $build"
            family != null -> family!!
            build != null -> build!!
            version != null -> "driver $version"
            else -> return
        }
        if (label != last) { last = label; onFound(label) }
    }
}
