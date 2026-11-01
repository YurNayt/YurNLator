package com.winlator.cmod

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.contents.ContentProfile
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.ProtonVersions
import com.winlator.cmod.core.RemoteFetch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.winlator.cmod.ui.container.ContainerCreateComposeFragment
import com.winlator.cmod.ui.containers.ContainerUiModel
import com.winlator.cmod.ui.containers.ContainersCallbacks
import com.winlator.cmod.ui.containers.ContainersComposeHost

/**
 * "Containers" tab of the main screen. In Ludashi 4.1 this fragment only redirected
 * to the library; YurNLator shows the containers list here (same actions as
 * Settings > Containers: run, edit, duplicate, remove, info, add).
 */
class ContainersFragment : Fragment(), ContainersCallbacks {
    private var host: ContainersComposeHost? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val h = ContainersComposeHost(requireContext(), this)
        host = h
        return h.createView()
    }

    override fun onResume() {
        super.onResume()
        // The toolbar is shared by all tabs; without this it keeps "Library" / "Settings".
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.setTitle(R.string.containers)
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // After the editor's exit fade, so the toolbar doesn't pop in over the closing editor.
        if (!landscape) view?.postDelayed({ (activity as? MainActivity)?.setMainToolbarVisible(true) }, 120)
        refresh()
    }

    override fun onDestroyView() {
        host = null
        super.onDestroyView()
    }

    private fun refresh() {
        val ctx = context ?: return
        val containers = ContainerManager(ctx).containers.toList()
        host?.submitList(containers.map { c ->
            ContainerUiModel(c.id, c.name, c.wineVersion.orEmpty(), c.screenSize.orEmpty())
        })
        // Then look for newer Proton / Wine builds (saved catalogs only, no waiting on the network).
        viewLifecycleOwner.lifecycleScope.launch {
            val updates = withContext(Dispatchers.IO) { runCatching { protonUpdates(ctx, containers) }.getOrDefault(emptyMap()) }
            if (updates.isEmpty()) return@launch
            host?.submitList(containers.map { c ->
                ContainerUiModel(c.id, c.name, c.wineVersion.orEmpty(), c.screenSize.orEmpty(), updates[c.id])
            })
        }
    }

    /** container id -> name of a newer build of its Wine / Proton line. */
    private fun protonUpdates(ctx: android.content.Context, containers: List<com.winlator.cmod.container.Container>): Map<Int, String> {
        val manager = ContentsManager(ctx)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val url = prefs.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES) ?: ContentsManager.REMOTE_PROFILES
        manager.setRemoteProfiles(RemoteFetch.cached(ctx, url) ?: "[]")
        val runtimes = listOf(ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON)
            .flatMap { manager.getProfiles(it).orEmpty() }
        val names = runtimes.map { it.verName }
        return containers.mapNotNull { c ->
            val current = runtimes.firstOrNull { ContentsManager.getEntryName(it) == c.wineVersion }?.verName ?: c.wineVersion
            ProtonVersions.newerThan(current.orEmpty(), names)?.let { c.id to it }
        }.toMap()
    }

    /** Opens the Compose container editor above the tabs; back returns here and refreshes. */
    private fun openEditor(fragment: Fragment) {
        // The editor has its own header with a back arrow: hide the "Containers" toolbar
        // above it (it comes back in onResume when the editor closes).
        (activity as? MainActivity)?.setMainToolbarVisible(false)
        parentFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.nav_enter, R.anim.nav_exit, R.anim.nav_enter, R.anim.nav_exit)
            .addToBackStack(null)
            .replace(R.id.FLFragmentContainer, fragment)
            .commit()
    }

    override fun onAdd() = openEditor(ContainerCreateComposeFragment())

    override fun onEdit(containerId: Int) = openEditor(ContainerCreateComposeFragment.forEdit(containerId))

    override fun onRun(containerId: Int) {
        val ctx = requireContext()
        if (!XrActivity.isEnabled(ctx)) {
            startActivity(Intent(ctx, XServerDisplayActivity::class.java).putExtra("container_id", containerId))
        } else {
            XrActivity.openIntent(requireActivity(), containerId, null)
        }
    }

    override fun onDuplicate(containerId: Int) {
        val ctx = requireContext()
        val manager = ContainerManager(ctx)
        val container = manager.getContainerById(containerId) ?: return
        manager.duplicateContainerAsync(container) {
            refresh()
            Toast.makeText(ctx, "Container duplicated", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRemove(containerId: Int) {
        val ctx = requireContext()
        val manager = ContainerManager(ctx)
        val container = manager.getContainerById(containerId) ?: return
        AlertDialog.Builder(ctx)
            .setTitle("Remove container?")
            .setMessage("${container.name} and its container files will be deleted.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Remove") { _, _ ->
                manager.removeContainerAsync(container) {
                    refresh()
                    Toast.makeText(ctx, "Container removed", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    override fun onInfo(containerId: Int) {
        val ctx = requireContext()
        val container = ContainerManager(ctx).getContainerById(containerId) ?: return
        AlertDialog.Builder(ctx)
            .setTitle(container.name)
            .setMessage(
                "Wine / Proton: ${container.wineVersion}\n" +
                    "Screen size: ${container.screenSize}\n" +
                    "Graphics driver: ${container.graphicsDriver}\n" +
                    "DX wrapper: ${container.getDXWrapper()}\n" +
                    "Folder: ${container.rootDir.absolutePath}"
            )
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Repair Wine files") { _, _ -> repairWine(container) }
            .show()
    }

    /**
     * Copies the system DLLs of the container's own Wine / Proton again and lets Wine update the
     * prefix on the next start. For containers switched between versions while the app resolved
     * catalog Proton builds to the built-in Proton 9 (they got Proton 9 files under Proton 11).
     */
    private fun repairWine(container: com.winlator.cmod.container.Container) {
        val ctx = context?.applicationContext ?: return
        Toast.makeText(ctx, "Repairing ${container.name}…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val contents = ContentsManager(ctx).apply { syncContents() }
                    ContainerManager(ctx).switchWineVersion(container, container.wineVersion, contents)
                }.getOrDefault(false)
            }
            Toast.makeText(ctx, if (ok) "Done: Wine updates the container on the next start" else "Could not repair: is this Wine / Proton still installed?", Toast.LENGTH_LONG).show()
        }
    }
}
