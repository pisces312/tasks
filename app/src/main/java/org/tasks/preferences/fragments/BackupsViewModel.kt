package org.tasks.preferences.fragments

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.JsonReader
import androidx.documentfile.provider.DocumentFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tasks.R
import org.tasks.analytics.Firebase
import org.tasks.extensions.Context.is24HourFormat
import org.tasks.extensions.jsonString
import org.tasks.extensions.lenientJson
import org.tasks.files.FileHelper
import org.tasks.kmp.org.tasks.time.getFullDateTime
import org.tasks.preferences.Preferences
import org.tasks.preferences.PreferencesViewModel
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import timber.log.Timber
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class BackupsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: Preferences,
    private val firebase: Firebase,
) : ViewModel() {

    var backupDirSummary by mutableStateOf("")
        private set
    var showBackupDirWarning by mutableStateOf(false)
        private set
    var lastBackupSummary by mutableStateOf("")
        private set
    var showLocalBackupWarning by mutableStateOf(false)
        private set
    var backupsEnabled by mutableStateOf(preferences.getBoolean(R.string.p_backups_enabled, true))
        private set
    var driveBackupEnabled by mutableStateOf(false)
        private set
    var driveAccountSummary by mutableStateOf("")
        private set
    var driveAccountEnabled by mutableStateOf(false)
        private set
    var lastDriveBackupSummary by mutableStateOf("")
        private set
    var showDriveBackupWarning by mutableStateOf(false)
        private set
    var androidBackupEnabled by mutableStateOf(
        preferences.getBoolean(R.string.p_backups_android_backup_enabled, true)
    )
        private set
    var lastAndroidBackupSummary by mutableStateOf("")
        private set
    var showAndroidBackupWarning by mutableStateOf(false)
        private set
    var ignoreWarnings by mutableStateOf(
        preferences.getBoolean(R.string.p_backups_ignore_warnings, false)
    )
        private set
    var settingsExportResult by mutableStateOf<String?>(null)
        private set
    var settingsImportResult by mutableStateOf<String?>(null)
        private set

    val backupDirectory: Uri?
        get() = preferences.backupDirectory

    fun refreshState(preferencesViewModel: PreferencesViewModel) {
        backupsEnabled = preferences.getBoolean(R.string.p_backups_enabled, true)
        androidBackupEnabled = preferences.getBoolean(
            R.string.p_backups_android_backup_enabled, true,
        )
        ignoreWarnings = preferences.getBoolean(R.string.p_backups_ignore_warnings, false)
        settingsExportResult = null
        settingsImportResult = null
        refreshDriveState(preferencesViewModel)
        refreshWarnings(preferencesViewModel)
        preferencesViewModel.updateBackups()
    }

    fun updateBackupsEnabled(enabled: Boolean) {
        preferences.setBoolean(R.string.p_backups_enabled, enabled)
        backupsEnabled = enabled
    }

    fun disableDriveBackup(preferencesViewModel: PreferencesViewModel) {
        preferences.remove(R.string.p_backups_drive_last)
        preferences.remove(R.string.p_google_drive_backup_account)
        preferencesViewModel.updateDriveBackup()
        refreshDriveState(preferencesViewModel)
    }

    fun updateAndroidBackup(enabled: Boolean, preferencesViewModel: PreferencesViewModel) {
        preferences.setBoolean(R.string.p_backups_android_backup_enabled, enabled)
        androidBackupEnabled = enabled
        refreshAndroidBackupSummary(preferencesViewModel.lastAndroidBackup.value, preferencesViewModel)
    }

    fun updateIgnoreWarnings(enabled: Boolean, preferencesViewModel: PreferencesViewModel) {
        preferences.setBoolean(R.string.p_backups_ignore_warnings, enabled)
        ignoreWarnings = enabled
        refreshWarnings(preferencesViewModel)
    }

    fun handleBackupDirResult(uri: Uri, preferencesViewModel: PreferencesViewModel) {
        preferences.setUri(R.string.p_backup_dir, uri)
        refreshBackupDirectory(preferencesViewModel)
        preferencesViewModel.updateLocalBackup()
    }

    fun logEvent(type: String) {
        firebase.logEvent(
            R.string.event_settings_click,
            R.string.param_type to type,
        )
    }

    fun refreshDriveState(preferencesViewModel: PreferencesViewModel) {
        val account = preferencesViewModel.driveAccount
        driveBackupEnabled = account != null
        driveAccountEnabled = account != null
        driveAccountSummary = account?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.none)
    }

    fun refreshWarnings(preferencesViewModel: PreferencesViewModel) {
        refreshBackupDirectory(preferencesViewModel)
        refreshLocalBackupSummary(preferencesViewModel.lastBackup.value, preferencesViewModel)
        refreshDriveBackupSummary(preferencesViewModel.lastDriveBackup.value, preferencesViewModel)
        refreshAndroidBackupSummary(preferencesViewModel.lastAndroidBackup.value, preferencesViewModel)
    }

    fun refreshLocalBackupSummary(timestamp: Long?, preferencesViewModel: PreferencesViewModel) {
        lastBackupSummary = formatLastBackup(timestamp)
        showLocalBackupWarning = preferences.showBackupWarnings() && preferencesViewModel.staleLocalBackup
    }

    fun refreshDriveBackupSummary(timestamp: Long?, preferencesViewModel: PreferencesViewModel) {
        lastDriveBackupSummary = formatLastBackup(timestamp)
        showDriveBackupWarning = preferences.showBackupWarnings() && preferencesViewModel.staleRemoteBackup
    }

    fun refreshAndroidBackupSummary(timestamp: Long? = null, preferencesViewModel: PreferencesViewModel) {
        lastAndroidBackupSummary = formatLastBackup(timestamp)
        showAndroidBackupWarning = preferences.showBackupWarnings() && preferencesViewModel.staleRemoteBackup
    }

    private fun refreshBackupDirectory(preferencesViewModel: PreferencesViewModel) {
        val location = FileHelper.uri2String(preferences.backupDirectory) ?: ""
        if (preferences.showBackupWarnings() && preferencesViewModel.usingPrivateStorage) {
            showBackupDirWarning = true
            val warning = context.getString(
                R.string.backup_location_warning,
                FileHelper.uri2String(preferences.appPrivateStorage),
            )
            backupDirSummary = "$location\n\n$warning"
        } else {
            showBackupDirWarning = false
            backupDirSummary = location
        }
    }

    fun exportSettings(preferencesViewModel: PreferencesViewModel, exportDirUri: Uri? = null) {
        logEvent("export_settings")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backupDir = exportDirUri ?: preferences.backupDirectory ?: run {
                    Timber.w("Export settings failed: backup directory not set")
                    settingsExportResult = context.getString(R.string.backup_directory_required)
                    return@launch
                }
                val documentFile = when (backupDir.scheme) {
                    ContentResolver.SCHEME_FILE -> {
                        val file = java.io.File(backupDir.path)
                        if (file.canWrite()) DocumentFile.fromFile(file) else null
                    }
                    ContentResolver.SCHEME_CONTENT -> DocumentFile.fromTreeUri(context, backupDir)
                    else -> null
                } ?: run {
                    Timber.w("Export settings failed: cannot access backup directory")
                    settingsExportResult = context.getString(R.string.backup_directory_required)
                    return@launch
                }
                val fileName = "settings_${getSettingsExportTimestamp()}"
                val newFile = documentFile.createFile("application/json", fileName) ?: run {
                    Timber.w("Export settings failed: cannot create file in backup directory")
                    settingsExportResult = context.getString(R.string.backup_directory_required)
                    return@launch
                }
                Timber.d("Exporting settings to ${newFile.uri}")
                context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
                    exportSettingsToStream(os)
                } ?: run {
                    Timber.w("Export settings failed: cannot open output stream")
                    settingsExportResult = context.getString(R.string.settings_export_error)
                    return@launch
                }
                Timber.i("Settings exported successfully to $fileName")
                settingsExportResult = context.getString(R.string.settings_exported)
            } catch (e: Exception) {
                Timber.e(e, "Failed to export settings")
                settingsExportResult = context.getString(R.string.settings_export_error)
            }
        }
    }

    fun importSettings(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { `is` ->
                    importSettingsFromStream(`is`)
                }
                settingsImportResult = context.getString(R.string.settings_imported)
            } catch (e: Exception) {
                Timber.e(e, "Failed to import settings")
                settingsImportResult = context.getString(R.string.settings_import_error)
            }
        }
    }

    private fun exportSettingsToStream(os: java.io.OutputStream) {
        val ignoreKeys = SETTINGS_EXPORT_IGNORE_KEYS.map { context.getString(it) }.toSet()
        Timber.d("Export ignore keys: $ignoreKeys")
        val allPrefs = preferences.getPrefs(java.lang.Object::class.java)
            .filterNot { (key, _) -> ignoreKeys.contains(key) }
        Timber.d("Exporting ${allPrefs.size} preferences")

        val intPrefs = mutableMapOf<String, Int>()
        val longPrefs = mutableMapOf<String, Long>()
        val stringPrefs = mutableMapOf<String, String>()
        val boolPrefs = mutableMapOf<String, Boolean>()
        val setPrefs = mutableMapOf<String, Set<String>>()

        for ((key, value) in allPrefs) {
            when (value) {
                is Int -> intPrefs[key] = value
                is Long -> longPrefs[key] = value
                is String -> stringPrefs[key] = value
                is Boolean -> boolPrefs[key] = value
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    setPrefs[key] = value as Set<String>
                }
                else -> Timber.w("Skipping unknown preference type: $key = $value (${value?.javaClass})")
            }
        }

        val writer = os.bufferedWriter(Charsets.UTF_8)
        writer.write("{")
        writer.write("\"version\":${org.tasks.BuildConfig.VERSION_CODE},")
        writer.write("\"timestamp\":${currentTimeMillis()}")
        if (intPrefs.isNotEmpty()) writer.write(",\"intPrefs\":${lenientJson.encodeToString(intPrefs)}")
        if (longPrefs.isNotEmpty()) writer.write(",\"longPrefs\":${lenientJson.encodeToString(longPrefs)}")
        if (stringPrefs.isNotEmpty()) writer.write(",\"stringPrefs\":${lenientJson.encodeToString(stringPrefs)}")
        if (boolPrefs.isNotEmpty()) writer.write(",\"boolPrefs\":${lenientJson.encodeToString(boolPrefs)}")
        if (setPrefs.isNotEmpty()) writer.write(",\"setPrefs\":${lenientJson.encodeToString(setPrefs)}")
        writer.write("}")
        writer.flush()
        Timber.d("Settings export stream written successfully")
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun importSettingsFromStream(`is`: java.io.InputStream) {
        val ignoreKeys = SETTINGS_EXPORT_IGNORE_KEYS.map { context.getString(it) }.toSet()
        val reader = JsonReader(InputStreamReader(`is`, Charsets.UTF_8))
        reader.isLenient = true
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "version" -> reader.nextInt()
                "timestamp" -> reader.nextLong()
                "intPrefs" ->
                    lenientJson.decodeFromString<Map<String, java.lang.Integer>>(reader.jsonString())
                        .filterNot { (key, _) -> ignoreKeys.contains(key) }
                        .forEach { (k, v) -> preferences.setInt(k, v as Int) }
                "longPrefs" ->
                    lenientJson.decodeFromString<Map<String, java.lang.Long>>(reader.jsonString())
                        .filterNot { (key, _) -> ignoreKeys.contains(key) }
                        .forEach { (k, v) -> preferences.setLong(k, v as Long) }
                "stringPrefs" ->
                    lenientJson.decodeFromString<Map<String, String>>(reader.jsonString())
                        .filterNot { (key, _) -> ignoreKeys.contains(key) }
                        .forEach { (k, v) -> preferences.setString(k, v) }
                "boolPrefs" ->
                    lenientJson.decodeFromString<Map<String, java.lang.Boolean>>(reader.jsonString())
                        .filterNot { (key, _) -> ignoreKeys.contains(key) }
                        .forEach { (k, v) -> preferences.setBoolean(k, v as Boolean) }
                "setPrefs" ->
                    lenientJson.decodeFromString<Map<String, Set<String>>>(reader.jsonString())
                        .filterNot { (key, _) -> ignoreKeys.contains(key) }
                        .forEach { (k, v) -> preferences.setStringSet(k, v as HashSet<String>) }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        reader.close()
    }

    private fun getSettingsExportTimestamp(): String {
        val dt = org.tasks.date.DateTimeUtils.newDateTime()
        return dt.toString("yyyyMMdd'T'HHmm")
    }

    private fun formatLastBackup(timestamp: Long?): String {
        val time = timestamp
            ?.takeIf { it >= 0 }
            ?.let { getFullDateTime(it, context.is24HourFormat) }
            ?: context.getString(R.string.last_backup_never)
        return context.getString(R.string.last_backup, time)
    }

    companion object {
        private val SETTINGS_EXPORT_IGNORE_KEYS = intArrayOf(
            R.string.p_current_version,
            R.string.p_install_version,
            R.string.p_install_date,
            R.string.p_device_install_version,
            R.string.p_just_updated,
            R.string.p_last_backup,
            R.string.p_backups_android_backup_last,
            R.string.p_backups_drive_last,
            R.string.p_last_sync,
            R.string.p_last_review_request,
            R.string.p_last_subscribe_request,
            R.string.p_git_sync_last,
            R.string.p_last_viewed_list,
            R.string.p_debug_pro,
            R.string.p_leakcanary,
            R.string.p_strict_mode_thread,
            R.string.p_strict_mode_vm,
            R.string.p_crash_main_queries,
            R.string.p_notified_oauth_error,
            R.string.p_local_list_banner_dismissed,
            R.string.p_shown_beast_mode_hint,
            R.string.p_backup_dir,
            R.string.p_attachment_dir,
        )
    }
}
