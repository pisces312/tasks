package org.tasks.backup

import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Collects Git sync related logs from Timber for display in the UI.
 * Only captures INFO and above priority, limited to a fixed number of entries.
 */
object GitSyncLogCollector {

    private const val MAX_LOGS = 200

    private val logs = ConcurrentLinkedQueue<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    data class LogEntry(
        val timestamp: Long,
        val priority: Int,
        val tag: String,
        val message: String,
    ) {
        val level: String
            get() = when (priority) {
                Log.INFO -> "INFO"
                Log.WARN -> "WARN"
                Log.ERROR -> "ERROR"
                Log.ASSERT -> "ASSERT"
                else -> "V${priority}"
            }

        val isError: Boolean
            get() = priority >= Log.ERROR

        val isWarning: Boolean
            get() = priority == Log.WARN

        fun format(): String {
            val time = dateFormat.format(Date(timestamp))
            return "$time [$level] $tag: $message"
        }
    }

    /**
     * Timber Tree that collects Git sync logs (INFO and above).
     * Only captures logs from the backup/git-sync package.
     */
    val timberTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < Log.INFO) return
            val safeTag = tag ?: return
            if (!isGitSyncRelated(safeTag)) return

            val fullMessage = if (t != null) {
                "$message\n${t.stackTraceToString()}"
            } else {
                message
            }
            addEntry(LogEntry(System.currentTimeMillis(), priority, safeTag, fullMessage))
        }

        private fun isGitSyncRelated(tag: String): Boolean {
            return tag.contains("GitSync", ignoreCase = true) ||
                    tag.contains("GitJson", ignoreCase = true) ||
                    tag.contains("SshKey", ignoreCase = true) ||
                    tag.contains("GitTransport", ignoreCase = true) ||
                    tag.contains("TasksSsh", ignoreCase = true) ||
                    tag.contains("TasksBackup", ignoreCase = true) ||
                    tag.contains("GitSyncLog", ignoreCase = true)
        }
    }

    private fun addEntry(entry: LogEntry) {
        logs.offer(entry)
        while (logs.size > MAX_LOGS) {
            logs.poll()
        }
    }

    fun getLogs(): List<LogEntry> = logs.toList()

    fun getFormattedLogs(): String = logs.joinToString("\n") { it.format() }

    fun clear() {
        logs.clear()
    }

    /**
     * Convenience methods for direct logging from GitSyncManager.
     * Only call Timber — timberTree will collect the entry automatically.
     */
    fun i(tag: String, message: String) {
        Timber.tag(tag).i(message)
    }

    fun w(tag: String, message: String) {
        Timber.tag(tag).w(message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.tag(tag).e(throwable, message)
        } else {
            Timber.tag(tag).e(message)
        }
    }
}
