package com.winlator.cmod.components

import com.winlator.cmod.components.DependencyDetector.Kind
import com.winlator.cmod.components.DependencyDetector.Recommendation
import java.io.File
import java.util.Locale

/**
 * What a game's exe needs, from what it imports ([PeImports]): Visual C++ runtimes, d3dx, XACT,
 * OpenAL, PhysX, .NET / XNA... that it doesn't ship beside itself. The import half of Bannerlator's
 * GameRecommendations (GPL-3.0); the Steam / store halves aren't ported.
 */
object GameImports {
    private const val MAX_OWN_DLLS = 12
    private const val MAX_BESIDE_DLLS = 24
    private const val XLIVE = "XLiveRedist"

    fun detect(exe: File): List<Recommendation> {
        val (imports, dotnet) = importEvidence(listOf(exe))
        return imports + listOfNotNull(dotnet)
    }

    private fun importEvidence(exes: List<File>): Pair<List<Recommendation>, Recommendation?> {
        val out = LinkedHashMap<String, Recommendation>()
        var dotnet: Recommendation? = null
        var scannedOwn = 0
        var scannedBeside = 0
        val besideRead = HashSet<String>()
        for (exe in exes) {
            val info = PeImports.read(exe) ?: continue
            val beside = exe.parentFile?.list()?.associateBy { it.lowercase(Locale.US) }.orEmpty()
            fun add(loader: String?, dll: String, is64: Boolean) {
                for (c in importComponents(dll, is64)) {
                    if (c in out) continue
                    val why = if (loader == null) "The game loads $dll" else "$loader loads $dll"
                    out[c] = Recommendation(c, DependencyDetector.labelOf(c), why, Kind.IMPORT)
                }
            }
            for (dll in info.imports) {
                val shipped = beside[dll]
                if (shipped == null) { add(null, dll, info.is64); continue }
                // A DLL of the game's own (UnityPlayer.dll, a game.dll): what it loads counts too.
                if (scannedOwn >= MAX_OWN_DLLS || importComponents(dll, info.is64).isNotEmpty()) continue
                scannedOwn++
                val own = PeImports.read(File(exe.parentFile, shipped)) ?: continue
                own.imports.filter { it !in beside }.forEach { add(shipped, it, own.is64) }
            }
            // Runtime DLLs the game ships beside itself (msvcp140.dll…) are skipped like imported ones.
            for ((lower, shipped) in beside.toSortedMap()) {
                if (scannedBeside >= MAX_BESIDE_DLLS) break
                if (!lower.endsWith(".dll") || lower in info.imports || importComponents(lower, info.is64).isNotEmpty()) continue
                val file = File(exe.parentFile, shipped)
                if (!besideRead.add(file.path)) continue
                scannedBeside++
                val own = PeImports.read(file) ?: continue
                own.imports.filter { it !in beside }.forEach { add(shipped, it, own.is64) }
            }
            if (dotnet == null && ("mscoree.dll" in info.imports || info.clrVersion != null)) {
                val clr = info.clrVersion.orEmpty()
                val v2 = clr.startsWith("v2") || clr.startsWith("v1")
                dotnet = Recommendation(if (v2) "dotnet35" else "dotnet48", DependencyDetector.labelOf(if (v2) "dotnet35" else "dotnet48"),
                    if (v2) "The game is a .NET 2.0-3.5 program" else "The game is a .NET program", Kind.IMPORT)
                if (info.usesXna) {
                    val xna = if (v2) "xna31" else "xna40"
                    if (xna !in out) out[xna] = Recommendation(xna, DependencyDetector.labelOf(xna), "The game uses XNA", Kind.IMPORT)
                }
            }
        }
        return out.values.toList() to dotnet
    }

    private val VC_BY_VERSION = mapOf(
        "80" to "vcredist2005", "90" to "vcredist2008", "100" to "vcredist2010",
        "110" to "vcredist2012", "120" to "vcredist2013", "140" to "vcredist2022",
    )
    /** XAudio 2.0-2.6 came with the XACT runtime; 2.8 and 2.9 are Windows' own. */
    private val XAUDIO_OLD = Regex("""^xaudio2_[0-6]\.dll$""")
    private val VC_RUNTIME = Regex("""^(?:msvc[rp]|mfc|mfcm|vcomp|concrt|vcamp|vccorlib)(\d{2,3})[a-z]?d?(?:_\d)?\.dll$""")

    /**
     * An imported [dll] to the components that provide it. XInput is never named (the app ships its
     * own for controller support); d3dx10 and the OS's own d3dcompiler_46/47 have no catalog
     * component; system DLLs map to nothing.
     */
    internal fun importComponents(dll: String, is64: Boolean): List<String> {
        val n = dll.lowercase(Locale.US)
        if (n == "mfc42.dll" || n == "mfc42u.dll") return listOf("mfc42")
        VC_RUNTIME.find(n)?.let { m -> return listOfNotNull(VC_BY_VERSION[m.groupValues[1]]) }
        return when {
            n.startsWith("vcruntime140") -> listOf("vcredist2022")
            n == "msvbvm60.dll" -> listOf("vbrun6")
            n.startsWith("d3dx9_") -> listOf("d3dx9")
            n.startsWith("d3dx11_") -> listOf("d3dx11")
            n == "d3dcompiler_43.dll" -> listOf("d3dcompiler_43")
            n == "d3dcompiler_42.dll" -> listOf("d3dcompiler_42")
            n == "xaudio2_7.dll" -> listOf("xaudio2.7")
            n.startsWith("x3daudio1_") || n.startsWith("xactengine") || n.startsWith("xapofx1_") || XAUDIO_OLD.matches(n) ->
                listOf(if (is64) "xact_x64" else "xact")
            n == "openal32.dll" || n == "wrap_oal.dll" -> listOf("oalinst")
            n == "physxloader.dll" || n == "physxcore.dll" || n == "physxcooking.dll" || n == "nxcooking.dll" -> listOf("physx")
            n == "xlive.dll" -> listOf(XLIVE)
            else -> emptyList()
        }
    }

}
