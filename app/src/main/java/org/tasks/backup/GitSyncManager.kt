package org.tasks.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.transport.RefSpec
import org.tasks.R
import org.tasks.preferences.Preferences
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class GitSyncResult {
    data class Success(val taskCount: Int) : GitSyncResult()
    data class NoChanges(val taskCount: Int) : GitSyncResult()
    data class Error(val message: String, val exception: Throwable? = null) : GitSyncResult()
}

@Singleton
class GitSyncManager @Inject constructor(
    private val gitJsonExporter: GitJsonExporter,
    private val sshKeyManager: SshKeyManager,
    private val preferences: Preferences,
    @ApplicationContext private val context: Context,
) {
    private val repoDir = File(context.filesDir, GIT_SYNC_DIR)

    val jsonFilePath: File
        get() = File(repoDir, GitJsonExporter.TASKS_JSON_FILENAME)

    val isRepoInitialized: Boolean
        get() = File(repoDir, ".git").exists()

    /**
     * Clone the remote repository or open the existing local clone.
     */
    suspend fun initOrCloneRepo(onStep: (GitSyncStep) -> Unit = {}): GitSyncResult = withContext(Dispatchers.IO) {
        val repoUrl = getRepoUrl()
        if (repoUrl.isBlank()) {
            return@withContext GitSyncResult.Error("Git repository URL not configured")
        }
        if (!sshKeyManager.hasKey) {
            return@withContext GitSyncResult.Error("SSH key not configured")
        }

        if (isRepoInitialized) {
            // Verify the remote URL matches
            try {
                val git = Git.open(repoDir)
                val storedUrl = git.repository.config.getString("remote", "origin", "url")
                if (storedUrl != repoUrl) {
                    // URL changed, update it
                    git.remoteSetUrl()
                        .setRemoteName("origin")
                        .setRemoteUri(org.eclipse.jgit.transport.URIish(repoUrl))
                        .call()
                    Timber.d("Updated remote URL from $storedUrl to $repoUrl")
                }
                git.close()
                return@withContext GitSyncResult.Success(0)
            } catch (e: Exception) {
                Timber.w(e, "Failed to open existing repo, will re-clone")
                repoDir.deleteRecursively()
            }
        }

        // Clone the repository
        onStep(GitSyncStep.CLONING)
        try {
            val git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(repoDir)
                .setBranch(getBranchName())
                .setTransportConfigCallback(GitTransportCallback(sshKeyManager.keyPath))
                .call()
            git.close()
            Timber.d("Cloned repository from $repoUrl")
            GitSyncResult.Success(0)
        } catch (e: TransportException) {
            Timber.e(e, "Transport error during clone")
            // Clean up failed clone
            repoDir.deleteRecursively()
            GitSyncResult.Error("Clone failed: ${e.localizedMessage}", e)
        } catch (e: Exception) {
            Timber.e(e, "Failed to clone repository")
            repoDir.deleteRecursively()
            GitSyncResult.Error("Clone failed: ${e.localizedMessage}", e)
        }
    }

    /**
     * Perform the full sync: pull → export → add → commit → push
     */
    suspend fun syncToGit(onStep: (GitSyncStep) -> Unit = {}): GitSyncResult = withContext(Dispatchers.IO) {
        // Pre-flight checks
        val repoUrl = getRepoUrl()
        if (repoUrl.isBlank()) {
            return@withContext GitSyncResult.Error("Git repository URL not configured")
        }
        if (!sshKeyManager.hasKey) {
            return@withContext GitSyncResult.Error("SSH key not configured")
        }

        // Ensure repo is initialized
        if (!isRepoInitialized) {
            val initResult = initOrCloneRepo(onStep)
            if (initResult is GitSyncResult.Error) return@withContext initResult
        }

        try {
            val git = Git.open(repoDir)

            // Checkout the target branch
            checkoutBranch(git)

            // Pull remote changes first to avoid conflicts
            onStep(GitSyncStep.PULLING)
            pullRemote(git)

            // Export JSON to repo working directory
            onStep(GitSyncStep.EXPORTING)
            val taskCount = gitJsonExporter.exportTo(repoDir)

            // git add .
            git.add()
                .addFilepattern(".")
                .setRenormalize(false)
                .call()
            // Also stage deletions
            git.add()
                .setUpdate(true)
                .addFilepattern(".")
                .setRenormalize(false)
                .call()

            // Check if there are changes to commit
            val status = git.status().call()
            if (!status.hasUncommittedChanges()) {
                git.close()
                return@withContext GitSyncResult.NoChanges(taskCount)
            }

            // git commit
            onStep(GitSyncStep.COMMITTING)
            val authorName = getAuthorName()
            val authorEmail = getAuthorEmail()
            if (authorName.isBlank() || authorEmail.isBlank()) {
                git.close()
                return@withContext GitSyncResult.Error("Git author name and email must be configured")
            }

            val commitMessage = buildCommitMessage(taskCount)
            git.commit()
                .setCommitter(authorName, authorEmail)
                .setAuthor(authorName, authorEmail)
                .setMessage(commitMessage)
                .call()

            // git push
            onStep(GitSyncStep.PUSHING)
            val branchName = getBranchName()
            git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec(branchName))
                .setTransportConfigCallback(GitTransportCallback(sshKeyManager.keyPath))
                .call()

            git.close()

            // Update last sync time
            preferences.setLong(R.string.p_git_sync_last, System.currentTimeMillis())

            Timber.d("Sync complete: $taskCount tasks pushed")
            GitSyncResult.Success(taskCount)
        } catch (e: TransportException) {
            Timber.e(e, "Transport error during sync")
            GitSyncResult.Error("Sync failed: ${e.localizedMessage}. Check your SSH key.", e)
        } catch (e: Exception) {
            Timber.e(e, "Sync failed")
            GitSyncResult.Error("Sync failed: ${e.localizedMessage}", e)
        }
    }

    /**
     * Pull remote changes (rebase strategy to keep linear history).
     */
    private fun pullRemote(git: Git) {
        try {
            git.pull()
                .setRemote("origin")
                .setRemoteBranchName(getBranchName())
                .setStrategy(MergeStrategy.THEIRS)
                .setTransportConfigCallback(GitTransportCallback(sshKeyManager.keyPath))
                .setRebase(true)
                .call()
            Timber.d("Pull succeeded")
        } catch (e: Exception) {
            Timber.w(e, "Pull failed, will attempt to push anyway")
        }
    }

    /**
     * Checkout the target branch, creating it from origin if it doesn't exist locally.
     */
    private fun checkoutBranch(git: Git) {
        val branchName = getBranchName()
        try {
            val existing: Ref? = git.repository.exactRef("refs/heads/$branchName")
            if (existing == null) {
                // Branch doesn't exist locally, try to track from remote
                val remoteBranch = "origin/$branchName"
                val remoteRef = git.repository.resolve(remoteBranch)
                if (remoteRef != null) {
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(branchName)
                        .setStartPoint(remoteBranch)
                        .call()
                } else {
                    // Neither local nor remote branch exists, create a new orphan branch
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(branchName)
                        .call()
                }
            } else {
                git.checkout()
                    .setName(branchName)
                    .call()
            }
        } catch (e: Exception) {
            Timber.w(e, "Branch checkout failed for $branchName")
        }
    }

    /**
     * Delete the local git repo to force a fresh clone on next sync.
     */
    fun resetLocalRepo() {
        repoDir.deleteRecursively()
        Timber.d("Local git repo deleted")
    }

    // --- Preference accessors ---

    fun getRepoUrl(): String =
        preferences.getStringValue(R.string.p_git_sync_repo_url) ?: ""

    fun setRepoUrl(url: String) =
        preferences.setString(R.string.p_git_sync_repo_url, url)

    fun getBranchName(): String =
        preferences.getStringValue(R.string.p_git_sync_branch) ?: DEFAULT_BRANCH

    fun setBranchName(branch: String) =
        preferences.setString(R.string.p_git_sync_branch, branch)

    fun getAuthorName(): String =
        preferences.getStringValue(R.string.p_git_sync_author_name) ?: ""

    fun setAuthorName(name: String) =
        preferences.setString(R.string.p_git_sync_author_name, name)

    fun getAuthorEmail(): String =
        preferences.getStringValue(R.string.p_git_sync_author_email) ?: ""

    fun setAuthorEmail(email: String) =
        preferences.setString(R.string.p_git_sync_author_email, email)

    val isEnabled: Boolean
        get() = preferences.getBoolean(R.string.p_git_sync_enabled, false)

    fun setEnabled(enabled: Boolean) =
        preferences.setBoolean(R.string.p_git_sync_enabled, enabled)

    val lastSyncTime: Long
        get() = preferences.getLong(R.string.p_git_sync_last, 0L)

    private fun buildCommitMessage(taskCount: Int): String {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        return "sync: $taskCount tasks at $timestamp"
    }

    companion object {
        private const val GIT_SYNC_DIR = "git-sync"
        private const val DEFAULT_BRANCH = "main"
    }
}
