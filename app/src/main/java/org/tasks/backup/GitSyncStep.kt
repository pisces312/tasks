package org.tasks.backup

/**
 * Represents the current step during a Git sync operation.
 * Used for lightweight progress display in the UI.
 */
enum class GitSyncStep {
    IDLE,
    CLONING,
    PULLING,
    EXPORTING,
    COMMITTING,
    PUSHING,
}
