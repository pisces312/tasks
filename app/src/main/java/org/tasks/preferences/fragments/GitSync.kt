package org.tasks.preferences.fragments

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.fragment.compose.content
import dagger.hilt.android.AndroidEntryPoint
import org.tasks.R
import org.tasks.compose.settings.GitSyncScreen
import org.tasks.extensions.Context.toast
import org.tasks.themes.TasksSettingsTheme
import org.tasks.themes.Theme
import javax.inject.Inject

@AndroidEntryPoint
class GitSync : Fragment() {

    @Inject lateinit var theme: Theme

    private val viewModel: GitSyncViewModel by viewModels()

    private val sshKeyPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                importSshKey(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        TasksSettingsTheme(
            theme = theme.themeBase.index,
            primary = theme.themeColor.primaryColor,
        ) {
            GitSyncScreen(
                isEnabled = viewModel.isEnabled,
                repoUrl = viewModel.repoUrl,
                branch = viewModel.branch,
                authorName = viewModel.authorName,
                authorEmail = viewModel.authorEmail,
                hasSshKey = viewModel.hasSshKey,
                sshKeyInfo = viewModel.sshKeyInfo,
                lastSyncSummary = viewModel.lastSyncSummary,
                syncStep = viewModel.syncStep,
                syncResult = viewModel.syncResult,
                logEntries = viewModel.logEntries,
                isLogExpanded = viewModel.isLogExpanded,
                onEnabledChanged = { viewModel.updateEnabled(it) },
                onRepoUrlChanged = { viewModel.updateRepoUrl(it) },
                onBranchChanged = { viewModel.updateBranch(it) },
                onAuthorNameChanged = { viewModel.updateAuthorName(it) },
                onAuthorEmailChanged = { viewModel.updateAuthorEmail(it) },
                onSelectSshKey = { openSshKeyPicker() },
                onDeleteSshKey = { viewModel.deleteSshKey() },
                onSyncNow = { viewModel.syncNow() },
                onViewJson = { showJsonViewer() },
                onResetRepo = { confirmResetRepo() },
                onClearResult = { viewModel.clearSyncResult() },
                onToggleLogExpanded = { viewModel.toggleLogExpanded() },
                onClearLogs = { viewModel.clearLogs() },
            )
        }
    }

    private fun openSshKeyPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, "id_rsa")
        }
        try {
            sshKeyPickerLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            context?.toast(R.string.git_sync_ssh_key_invalid)
        }
    }

    private fun importSshKey(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // Not all content providers support persistent permissions
        }
        viewModel.importSshKey(uri)
    }

    private fun showJsonViewer() {
        viewModel.loadJsonContent()
        val jsonContent = viewModel.jsonContent

        if (jsonContent == null) {
            context?.toast(R.string.git_sync_no_json)
            return
        }

        val scrollView = ScrollView(requireContext())
        val textView = TextView(requireContext()).apply {
            text = jsonContent
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(32, 32, 32, 32)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.git_sync_view_json)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(android.R.string.copy) { _, _ ->
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("tasks.json", jsonContent))
                context?.toast("Copied to clipboard")
            }
            .show()
    }

    private fun confirmResetRepo() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.git_sync_reset)
            .setMessage(R.string.git_sync_reset_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.resetLocalRepo()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
