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

    private fun tag() = Timber.tag("GitSyncManager")

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
                    tag().i("Updated remote URL from $storedUrl to $repoUrl")
                }
                git.close()
                return@withContext GitSyncResult.Success(0)
            } catch (e: Exception) {
                tag().w(e, "Failed to open existing repo, will re-clone")
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
            tag().i("Cloned repository from $repoUrl")
            GitSyncResult.Success(0)
        } catch (e: TransportException) {
            tag().e(e, "Transport error during clone")
            // Clean up failed clone
            repoDir.deleteRecursively()
            GitSyncResult.Error("Clone failed: ${e.localizedMessage}", e)
        } catch (e: Exception) {
            tag().e(e, "Failed to clone repository")
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
            tag().i("Repo not initialized, will clone")
            val initResult = initOrCloneRepo(onStep)
            if (initResult is GitSyncResult.Error) return@withContext initResult
        }

        try {
            val git = Git.open(repoDir)

            // Checkout the target branch
            val branchName = getBranchName()
            tag().i("Checking out branch $branchName")
            checkoutBranch(git)

            // Pull remote changes first to avoid conflicts
            onStep(GitSyncStep.PULLING)
            tag().i("Starting pull from remote ($branchName)")
            pullRemote(git)
            tag().i("Pull complete")

            // Export JSON to repo working directory
            onStep(GitSyncStep.EXPORTING)
            val taskCount = gitJsonExporter.exportTo(repoDir)
            tag().i("Exported ${taskCount} tasks to ${repoDir.absolutePath}")

            // git add .
            tag().i("Running git add")
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
            tag().i("git add complete")

            // Check if there are changes to commit
            val status = git.status().call()
            val hasChanges = status.hasUncommittedChanges()
            tag().i("Status: hasUncommittedChanges=$hasChanges")

            if (hasChanges) {
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
                tag().i("Committed: $commitMessage")
            } else {
                tag().i("No changes to commit (working tree clean)")
            }

            // git push - ALWAYS attempt push
            onStep(GitSyncStep.PUSHING)
            tag().i("Starting push to origin/$branchName")
            val pushRefSpec = RefSpec("refs/heads/$branchName:refs/heads/$branchName")
            try {
                git.push()
                    .setRemote("origin")
                    .setRefSpecs(pushRefSpec)
                    .setTransportConfigCallback(GitTransportCallback(sshKeyManager.keyPath))
                    .call()
                tag().i("Push successful")
            } catch (e: Exception) {
                tag().e(e, "Push failed")
                throw e
            }

            git.close()

            // Update last sync time
            preferences.setLong(R.string.p_git_sync_last, System.currentTimeMillis())

            val result = if (hasChanges) GitSyncResult.Success(taskCount) else GitSyncResult.NoChanges(taskCount)
            tag().i("Sync complete: ${if (hasChanges) "$taskCount tasks pushed" else "no changes, push done"}")
            result
        } catch (e: TransportException) {
            tag().e(e, "Transport error during sync")
            GitSyncResult.Error("Sync failed: ${e.localizedMessage}. Check your SSH key.", e)
        } catch (e: Exception) {
            tag().e(e, "Sync failed")
            GitSyncResult.Error("Sync failed: ${e.localizedMessage}", e)
        }
    }

    /**
     * Pull remote changes (rebase strategy to keep linear history).
     * If the remote branch doesn't exist yet (first push scenario), pull is skipped.
     */
    private fun pullRemote(git: Git) {
        val branchName = getBranchName()
        try {
            tag().i("Checking if remote branch %s exists...", branchName)
            // Check if remote branch exists before pulling
            val remoteBranchRef = git.lsRemote()
                .setRemote("origin")
                .setHeads(true)
                .setTransportConfigCallback(GitTransportCallback(sshKeyManager.keyPath))
                .call()
                .any { it.name == "refs/heads/$branchName" }

            if (!remoteBranchRef) {
                tag().i("Remote branch %s does not exist yet, skipping pull", branchName)
                return
            }

            tag().i("Remote branch exists, running git pull (rebase)")
            git.pull()
                .setRemote("origin")
                .setRemoteBranchName(branchName)
                .setStrategy(MergeStrategy.THEIRS)
                .setTransportConfigCallback(GitTransportCallback(sshKeyManager.keyPath))
                .setRebase(true)
                .call()
            tag().i("Pull succeeded")
        } catch (e: Exception) {
            tag().w(e, "Pull failed, will attempt to push anyway")
        }
    }

    /**
     * Checkout the target branch, creating it from origin if it doesn't exist locally.
     */
    private fun checkoutBranch(git: Git) {
        val branchName = getBranchName()
        try {
            tag().i("Checking local branch %s", branchName)
            val existing: Ref? = git.repository.exactRef("refs/heads/$branchName")
            if (existing == null) {
                tag().i("Local branch %s not found, checking remote...", branchName)
                // Branch doesn't exist locally, try to track from remote
                val remoteBranch = "origin/$branchName"
                val remoteRef = git.repository.resolve(remoteBranch)
                if (remoteRef != null) {
                    tag().i("Creating local branch %s tracking %s", branchName, remoteBranch)
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(branchName)
                        .setStartPoint(remoteBranch)
                        .call()
                } else {
                    // Neither local nor remote branch exists, create a new orphan branch
                    tag().i("Neither local nor remote branch exists, creating new branch %s", branchName)
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(branchName)
                        .call()
                }
            } else {
                tag().i("Checking out existing local branch %s", branchName)
                git.checkout()
                    .setName(branchName)
                    .call()
            }
            tag().i("Branch checkout complete: %s", branchName)
        } catch (e: Exception) {
            tag().e(e, "Branch checkout failed for %s", branchName)
        }
    }

    /**
     * Delete the local git repo to force a fresh clone on next sync.
     */
    fun resetLocalRepo() {
        repoDir.deleteRecursively()
        tag().i("Local git repo deleted")
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
