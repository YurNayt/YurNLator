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
        if (!landscape) (activity as? MainActivity)?.setMainToolbarVisible(true)
        refresh()
    }

    override fun onDestroyView() {
        host = null
        super.onDestroyView()
    }

    private fun refresh() {
        val ctx = context ?: return
        val list = ContainerManager(ctx).containers.map { c ->
            ContainerUiModel(c.id, c.name, c.wineVersion.orEmpty(), c.screenSize.orEmpty())
        }
        host?.submitList(list)
    }

    /** Opens the Compose container editor above the tabs; back returns here and refreshes. */
    private fun openEditor(fragment: Fragment) {
        // The editor has its own header with a back arrow: hide the "Containers" toolbar
        // above it (it comes back in onResume when the editor closes).
        (activity as? MainActivity)?.setMainToolbarVisible(false)
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
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
            .show()
    }
}
