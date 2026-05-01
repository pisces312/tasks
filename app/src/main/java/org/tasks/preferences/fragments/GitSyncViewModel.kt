package org.tasks.preferences.fragments

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.tasks.R
import org.tasks.backup.GitSyncLogCollector
import org.tasks.backup.GitSyncManager
import org.tasks.backup.GitSyncResult
import org.tasks.backup.GitSyncStep
import org.tasks.backup.SshKeyManager
import org.tasks.extensions.Context.is24HourFormat
import org.tasks.kmp.org.tasks.time.getFullDateTime
import javax.inject.Inject

@HiltViewModel
class GitSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitSyncManager: GitSyncManager,
    private val sshKeyManager: SshKeyManager,
) : ViewModel() {

    var isEnabled by mutableStateOf(gitSyncManager.isEnabled)
        private set
    var repoUrl by mutableStateOf(gitSyncManager.getRepoUrl())
        private set
    var branch by mutableStateOf(gitSyncManager.getBranchName())
        private set
    var authorName by mutableStateOf(gitSyncManager.getAuthorName())
        private set
    var authorEmail by mutableStateOf(gitSyncManager.getAuthorEmail())
        private set
    var hasSshKey by mutableStateOf(sshKeyManager.hasKey)
        private set
    var sshKeyInfo by mutableStateOf(buildKeyInfo())
        private set
    var lastSyncSummary by mutableStateOf(buildLastSyncSummary())
        private set
    var syncStep by mutableStateOf(GitSyncStep.IDLE)
        private set
    var syncResult by mutableStateOf<GitSyncResult?>(null)
        private set
    var jsonContent by mutableStateOf<String?>(null)
        private set
    var isLoadingJson by mutableStateOf(false)
        private set

    // Log panel state
    var logEntries by mutableStateOf(GitSyncLogCollector.getLogs())
        private set
    var isLogExpanded by mutableStateOf(false)
        private set

    fun updateEnabled(enabled: Boolean) {
        gitSyncManager.setEnabled(enabled)
        isEnabled = enabled
    }

    fun updateRepoUrl(url: String) {
        gitSyncManager.setRepoUrl(url)
        repoUrl = url
    }

    fun updateBranch(branch: String) {
        gitSyncManager.setBranchName(branch)
        this.branch = branch
    }

    fun updateAuthorName(name: String) {
        gitSyncManager.setAuthorName(name)
        authorName = name
    }

    fun updateAuthorEmail(email: String) {
        gitSyncManager.setAuthorEmail(email)
        authorEmail = email
    }

    fun onSshKeyImported(success: Boolean) {
        hasSshKey = sshKeyManager.hasKey
        sshKeyInfo = buildKeyInfo()
        if (!success) {
            syncResult = GitSyncResult.Error(context.getString(R.string.git_sync_ssh_key_invalid))
        }
    }

    /**
     * Import SSH key from a content URI. Called by the Fragment after SAF picker returns.
     */
    fun importSshKey(uri: Uri): Boolean {
        return try {
            val success = sshKeyManager.importKey(uri)
            onSshKeyImported(success)
            success
        } catch (e: Exception) {
            onSshKeyImported(false)
            false
        }
    }

    fun deleteSshKey() {
        sshKeyManager.deleteKey()
        hasSshKey = false
        sshKeyInfo = buildKeyInfo()
    }

    fun syncNow() {
        if (syncStep != GitSyncStep.IDLE) return
        viewModelScope.launch {
            syncStep = GitSyncStep.PULLING
            syncResult = null
            // Start log polling during sync
            val logPollJob = viewModelScope.launch {
                while (true) {
                    logEntries = GitSyncLogCollector.getLogs()
                    delay(300)
                }
            }
            try {
                val result = gitSyncManager.syncToGit { step ->
                    syncStep = step
                }
                syncResult = result
                lastSyncSummary = buildLastSyncSummary()
            } catch (e: Exception) {
                syncResult = GitSyncResult.Error(e.localizedMessage ?: "Unknown error", e)
            } finally {
                syncStep = GitSyncStep.IDLE
                logPollJob.cancel()
                // Final log update
                logEntries = GitSyncLogCollector.getLogs()
            }
        }
    }

    fun refreshLogs() {
        logEntries = GitSyncLogCollector.getLogs()
    }

    fun toggleLogExpanded() {
        isLogExpanded = !isLogExpanded
        if (isLogExpanded) {
            logEntries = GitSyncLogCollector.getLogs()
        }
    }

    fun clearLogs() {
        GitSyncLogCollector.clear()
        logEntries = emptyList()
    }

    fun loadJsonContent() {
        if (isLoadingJson) return
        viewModelScope.launch {
            isLoadingJson = true
            try {
                val file = gitSyncManager.jsonFilePath
                if (file.exists()) {
                    jsonContent = file.readText()
                } else {
                    jsonContent = null
                }
            } catch (e: Exception) {
                jsonContent = null
            } finally {
                isLoadingJson = false
            }
        }
    }

    fun clearSyncResult() {
        syncResult = null
    }

    fun resetLocalRepo() {
        gitSyncManager.resetLocalRepo()
        lastSyncSummary = buildLastSyncSummary()
    }

    private fun buildKeyInfo(): String {
        if (!sshKeyManager.hasKey) {
            return context.getString(R.string.git_sync_ssh_key_not_set)
        }
        val keyType = sshKeyManager.getKeyType() ?: "Unknown"
        val fingerprint = sshKeyManager.getKeyFingerprint() ?: ""
        return context.getString(R.string.git_sync_ssh_key_imported, keyType, fingerprint)
    }

    private fun buildLastSyncSummary(): String {
        val lastSync = gitSyncManager.lastSyncTime
        if (lastSync <= 0) {
            return context.getString(R.string.git_sync_last_never)
        }
        val timeStr = getFullDateTime(lastSync, context.is24HourFormat)
        return context.getString(R.string.git_sync_last, timeStr)
    }
}
