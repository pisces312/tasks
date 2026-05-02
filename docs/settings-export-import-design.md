# Settings Export/Import Design Document

> **Status**: Implemented  
> **Scope**: Android app (`org.tasks`) SharedPreferences standalone export/import feature

---

## 1. Overview

A lightweight mechanism to export and import app **settings only** (not tasks data) as a plain JSON file. This allows users to:
- Back up their preferences separately from task data
- Migrate settings across devices without a full backup restore
- Share a standardized settings configuration

---

## 2. Architecture

### 2.1 Storage Backend

Tasks.org persists all user preferences via **Android SharedPreferences** (`Context.getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)`).

- **No database involved** — settings are key-value pairs stored in an XML file inside the app's private directory
- All writes use `SharedPreferences.Editor.apply()` (asynchronous commit to disk)
- Reads are served from an in-memory cache

### 2.2 Data Flow

```
Export:
  User taps "Export settings"
    → BackupsViewModel.exportSettings()
    → Read all entries from SharedPreferences
    → Filter out runtime/temporary keys
    → Classify by type (int / long / string / boolean / string-set)
    → Serialize to JSON
    → Write to backup directory as settings_YYYYMMDDTHHMM.json

Import:
  User taps "Import settings" and selects a .json file
    → BackupsViewModel.importSettings(uri)
    → Parse JSON with JsonReader
    → Write each key back to SharedPreferences via Preferences.setXxx()
    → UI shows result message
```

---

## 3. JSON Schema

```json
{
  "version": 130800,
  "timestamp": 1714627845123,
  "intPrefs": {
    "p_fontSize": 16,
    "p_theme": 0
  },
  "longPrefs": {
    "p_install_date": 1714000000000
  },
  "stringPrefs": {
    "p_default_reminders_mode_key": "0"
  },
  "boolPrefs": {
    "p_backups_enabled": true
  },
  "setPrefs": {
    "p_purchases": ["purchase_json_1", "purchase_json_2"]
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `version` | int | App version code at export time |
| `timestamp` | long | Export time (epoch millis) |
| `intPrefs` | object | Integer preferences |
| `longPrefs` | object | Long preferences |
| `stringPrefs` | object | String preferences |
| `boolPrefs` | object | Boolean preferences |
| `setPrefs` | object | String-set preferences |

---

## 4. Exclusion List

The following 23 keys are **not exported** (runtime state, timestamps, debug flags, or file URIs):

| Key | Reason |
|-----|--------|
| `p_current_version` | Runtime — current app version |
| `p_install_version` | Runtime — version at first install |
| `p_install_date` | Runtime — install timestamp |
| `p_device_install_version` | Runtime — device-specific install marker |
| `p_just_updated` | Runtime — update flag |
| `p_last_backup` | Runtime — last local backup timestamp |
| `p_backups_android_backup_last` | Runtime — last Android Backup timestamp |
| `p_backups_drive_last` | Runtime — last Google Drive backup timestamp |
| `p_last_sync` | Runtime — last sync timestamp |
| `p_last_review_request` | Runtime — last review prompt timestamp |
| `p_last_subscribe_request` | Runtime — last subscription prompt timestamp |
| `p_git_sync_last` | Runtime — last Git sync timestamp |
| `p_last_viewed_list` | Runtime — navigation state |
| `p_debug_pro` | Debug flag |
| `p_leakcanary` | Debug flag |
| `p_strict_mode_thread` | Debug flag |
| `p_strict_mode_vm` | Debug flag |
| `p_crash_main_queries` | Debug flag |
| `p_notified_oauth_error` | Runtime — per-account OAuth error notification state |
| `p_local_list_banner_dismissed` | Runtime — UI banner state |
| `p_shown_beast_mode_hint` | Runtime — onboarding hint state |
| `p_backup_dir` | File URI — device-specific path |
| `p_attachment_dir` | File URI — device-specific path |

---

## 5. UI Placement

Located in **Settings → Backups** screen (`BackupsScreen.kt`):

1. **Git Sync** (top card)
2. **Documentation**
3. **Backup directory**
4. **Backup now**
5. **Import backup**
6. **Export settings** ← new
7. **Import settings** ← new
8. **Automatic backups** (toggle)

---

## 6. Implementation Details

### 6.1 Key Classes

| Class | Role |
|-------|------|
| `BackupsViewModel` | Export/import logic, state management |
| `BackupsScreen` | Compose UI — displays rows and result messages |
| `Backups` | Fragment host — wires activity result launchers |
| `Preferences` | SharedPreferences wrapper (get/set/clear) |

### 6.2 Export Method

```kotlin
// BackupsViewModel.exportSettingsToStream()
// 1. Collect all prefs via preferences.getPrefs(Object::class.java)
// 2. Filter out SETTINGS_EXPORT_IGNORE_KEYS
// 3. Partition by type into int/long/string/bool/set maps
// 4. Write JSON manually (minimal pretty-print)
```

### 6.3 Import Method

```kotlin
// BackupsViewModel.importSettingsFromStream()
// 1. Open JsonReader on selected file
// 2. Iterate object fields
// 3. For each typed map, decode and write to Preferences
// 4. Ignore unknown fields for forward compatibility
```

### 6.4 Result Feedback

| Action | Success Message | Failure Message |
|--------|-----------------|-----------------|
| Export | "Settings exported successfully" | "Failed to export settings" |
| Import | "Settings imported successfully. Restart app for some changes to take effect." | "Failed to import settings" |

Result text appears as the **summary** of the corresponding row in `BackupsScreen`.

---

## 7. Behavior Notes

### 7.1 Immediate vs. Deferred Effects

Most settings take effect **immediately** because the app reads SharedPreferences on demand. However, the following require an **Activity recreation or app restart**:

- Theme / accent color changes
- Language / locale changes
- Notification channel modifications
- Widget configuration updates

The import success message explicitly advises the user to restart.

### 7.2 File URI Preferences

`p_backup_dir` and `p_attachment_dir` are excluded because they contain device-specific content URIs or file paths that are meaningless on another device. After importing settings, the user must re-select the backup directory.

### 7.3 Version Compatibility

The `version` field in the export JSON is informational only. The importer does not enforce version matching — it skips unknown keys naturally via `else -> reader.skipValue()`.

---

## 8. Security Considerations

- Export files are written to the **user-selected backup directory** (same location as full backups)
- The JSON is **unencrypted** — it contains only preferences, no task data or credentials
- OAuth tokens and passwords are **not stored** in SharedPreferences and therefore never exported
- SSH private keys for Git sync are stored in a separate internal directory and are **never exported**

---

## 9. Future Improvements

- [ ] Encrypt settings export with the same mechanism as full backups
- [ ] Add "Reset to defaults" option
- [ ] Export/import individual preference categories (appearance, notifications, etc.)
- [ ] Cloud sync integration for settings file
