package org.tasks.ui

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tasks.R
import org.tasks.broadcast.ComposeRefreshBroadcaster
import org.tasks.data.TaskSaver
import org.tasks.service.TaskCompleter
import org.tasks.TasksApplication.Companion.IS_GENERIC
import org.tasks.TasksApplication.Companion.IS_GOOGLE_PLAY
import org.jetbrains.compose.resources.StringResource
import org.tasks.analytics.Firebase
import org.tasks.billing.Inventory
import org.tasks.data.dao.CaldavDao
import org.tasks.data.dao.DeletionDao
import org.tasks.data.dao.TaskDao
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.Task
import org.tasks.resources.getString
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.no_title
import org.tasks.data.composeTitle
import org.tasks.extensions.Context.canScheduleExactAlarms
import org.tasks.feed.BlogPost
import org.tasks.filters.SearchFilter
import org.tasks.preferences.PermissionChecker
import org.tasks.preferences.Preferences
import org.tasks.preferences.TasksPreferences
import org.tasks.service.TaskDeleter
import org.tasks.tasklist.HeaderFormatter
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import kotlinx.serialization.json.Json
import javax.inject.Inject

sealed class Banner(
    val eventType: String,
    val positiveLabel: String,
    val negativeLabel: String,
) {
    data object NotificationsDisabled : Banner("notifications", "TLA_menu_settings", "dismiss")
    data object AlarmsDisabled : Banner("alarms", "TLA_menu_settings", "dismiss")
    data class SubscriptionRequired(val nameRes: StringResource, val isTasksOrg: Boolean) : Banner("subscribe", "upgrade", "dismiss")
    data object BegForMoney : Banner(
        "subscribe",
        if (IS_GENERIC) "donate_today" else "upgrade",
        "donate_maybe_later",
    )
    data object WarnMicrosoft : Banner("microsoft", "button_learn_more", "dismiss")
    data object WarnGoogleTasks : Banner("google_tasks", "button_learn_more", "dismiss")
    data object AppUpdated : Banner("app_updated", "whats_new", "dismiss")
    data class BlogAnnouncement(val post: BlogPost) : Banner("blog", "read_more", "dismiss")
}

data class InlineTitleEditState(
    val taskId: Long,
    val originalTitle: String?,
    val draftTitle: String,
    val saving: Boolean = false,
)

data class InlineTitleCommitResult(
    val success: Boolean,
    val task: Task? = null,
)

@HiltViewModel
class TaskListViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val preferences: Preferences,
    private val taskDao: TaskDao,
    taskDeleter: TaskDeleter,
    deletionDao: DeletionDao,
    private val taskSaver: TaskSaver,
    taskCompleter: TaskCompleter,
    private val tasksPreferences: TasksPreferences,
    composeRefreshBroadcaster: ComposeRefreshBroadcaster,
    private val inventory: Inventory,
    private val firebase: Firebase,
    private val permissionChecker: PermissionChecker,
    private val caldavDao: CaldavDao,
    headerFormatter: HeaderFormatter,
) : org.tasks.viewmodel.TaskListViewModel(
    taskDao = taskDao,
    taskDeleter = taskDeleter,
    deletionDao = deletionDao,
    taskSaver = taskSaver,
    taskCompleter = taskCompleter,
    tasksPreferences = tasksPreferences,
    headerFormatter = headerFormatter,
    queryPreferences = preferences,
    isPerListSortEnabled = preferences.isPerListSortEnabled,
    createSearchFilter = { query ->
        SearchFilter(applicationContext.getString(R.string.FLA_search_filter, query), query)
    },
    refreshFlow = composeRefreshBroadcaster.refreshes,
) {
    private val _banner = MutableStateFlow<Banner?>(null)
    val banner = _banner.asStateFlow()

    private val _inlineTitleEditState = MutableStateFlow<InlineTitleEditState?>(null)
    val inlineTitleEditState = _inlineTitleEditState.asStateFlow()

    private val _inlineTitleSaveFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val inlineTitleSaveFailures = _inlineTitleSaveFailures.asSharedFlow()

    private val _inlineTitleCommitResults =
        MutableSharedFlow<InlineTitleCommitResult>(extraBufferCapacity = 1)
    val inlineTitleCommitResults = _inlineTitleCommitResults.asSharedFlow()

    fun beginInlineTitleEdit(taskId: Long, title: String?) {
        _inlineTitleEditState.value = InlineTitleEditState(
            taskId = taskId,
            originalTitle = title,
            draftTitle = title.orEmpty(),
        )
    }

    fun updateInlineTitleDraft(taskId: Long, title: String) {
        _inlineTitleEditState.value
            ?.takeIf { it.taskId == taskId && !it.saving }
            ?.let { current ->
                _inlineTitleEditState.value = current.copy(draftTitle = title)
            }
    }

    fun cancelInlineTitleEdit() {
        _inlineTitleEditState.value = null
    }

    /** Returns true when no asynchronous save is required. */
    fun commitInlineTitleEdit(): Boolean {
        val state = _inlineTitleEditState.value ?: return true
        if (state.saving) {
            return false
        }
        if (state.originalTitle.orEmpty() == state.draftTitle) {
            _inlineTitleEditState.value = null
            return true
        }
        _inlineTitleEditState.value = state.copy(saving = true)
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    val current = taskDao.fetch(state.taskId)
                    if (current == null || current.isDeleted || current.readOnly) {
                        _inlineTitleEditState.value = null
                        invalidate()
                        _inlineTitleSaveFailures.emit(Unit)
                        _inlineTitleCommitResults.emit(InlineTitleCommitResult(success = false))
                        return@withContext
                    }
                    val title = state.draftTitle.takeIf { it.isNotBlank() }
                        ?: getString(Res.string.no_title)
                    val updated = current.copy(title = title)
                    taskSaver.save(updated, current, preserveHierarchy = true)
                    val saved = taskDao.fetch(state.taskId)
                    check(saved?.title == title) { "Task title update was not persisted" }
                    _inlineTitleEditState.value = null
                    _inlineTitleCommitResults.emit(
                        InlineTitleCommitResult(success = true, task = updated)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    _inlineTitleEditState.value = state.copy(saving = false)
                    _inlineTitleSaveFailures.emit(Unit)
                    _inlineTitleCommitResults.emit(InlineTitleCommitResult(success = false))
                }
            }
        }
        return false
    }

    fun updateBannerState() {
        viewModelScope.launch(Dispatchers.IO) {
            val accounts = caldavDao.getAccounts()
            val dismissedSubscriptionAccounts = tasksPreferences.get(
                TasksPreferences.subscriptionDismissedAccounts, emptySet()
            )
            val resolvedAccounts = accounts
                .filter { it.uuid in dismissedSubscriptionAccounts && !it.isSubscriptionRequired() }
                .mapNotNull { it.uuid }
                .toSet()
            if (resolvedAccounts.isNotEmpty()) {
                tasksPreferences.set(
                    TasksPreferences.subscriptionDismissedAccounts,
                    dismissedSubscriptionAccounts - resolvedAccounts
                )
            }
            val subscriptionAccounts = if (IS_GOOGLE_PLAY) {
                accounts.filter {
                    it.isSubscriptionRequired() && it.uuid !in dismissedSubscriptionAccounts
                }
            } else {
                emptyList()
            }
            val banner = when {
                subscriptionAccounts.isNotEmpty() -> {
                    val account = subscriptionAccounts.find { it.isTasksOrg }
                        ?: subscriptionAccounts.first()
                    Banner.SubscriptionRequired(
                        nameRes = account.composeTitle!!,
                        isTasksOrg = account.isTasksOrg,
                    )
                }
                preferences.getBoolean(R.string.p_just_updated, false) ->
                    Banner.AppUpdated
                preferences.warnNotificationsDisabled && !permissionChecker.hasNotificationPermission() ->
                    Banner.NotificationsDisabled
                preferences.warnAlarmsDisabled && !applicationContext.canScheduleExactAlarms() ->
                    Banner.AlarmsDisabled
                inventory.begForMoney && !firebase.subscribeCooldown ->
                    Banner.BegForMoney
                accounts.any { it.isMicrosoft } && preferences.warnMicrosoft ->
                    Banner.WarnMicrosoft
                accounts.any { it.isGoogleTasks } && preferences.warnGoogleTasks ->
                    Banner.WarnGoogleTasks
                else -> pendingBlogPost()
            }
            if (banner != null && banner != _banner.value) {
                firebase.logEvent(
                    R.string.event_banner,
                    R.string.param_type to banner.eventType,
                    R.string.param_action to "shown",
                )
            }
            _banner.value = banner
        }
    }

    fun dismissBanner(tookAction: Boolean = false) {
        val currentBanner = _banner.value ?: return

        viewModelScope.launch(NonCancellable) {
            when (currentBanner) {
                Banner.NotificationsDisabled -> preferences.warnNotificationsDisabled = tookAction
                Banner.AlarmsDisabled -> preferences.warnAlarmsDisabled = tookAction
                is Banner.SubscriptionRequired -> {
                    withContext(Dispatchers.IO) {
                        val dismissed = tasksPreferences.get(
                            TasksPreferences.subscriptionDismissedAccounts, emptySet()
                        )
                        val uuids = caldavDao.getAccounts()
                            .filter { it.isSubscriptionRequired() }
                            .mapNotNull { it.uuid }
                        tasksPreferences.set(
                            TasksPreferences.subscriptionDismissedAccounts,
                            dismissed + uuids
                        )
                    }
                }
                Banner.BegForMoney -> preferences.lastSubscribeRequest = currentTimeMillis()
                Banner.WarnGoogleTasks -> preferences.warnGoogleTasks = false
                Banner.WarnMicrosoft -> preferences.warnMicrosoft = false
                Banner.AppUpdated -> preferences.setBoolean(R.string.p_just_updated, false)
                is Banner.BlogAnnouncement -> {
                    tasksPreferences.set(
                        TasksPreferences.blogDismissedPostId,
                        currentBanner.post.guid,
                    )
                    tasksPreferences.set(TasksPreferences.blogPendingPost, "")
                }
            }
            val action = if (tookAction) "positive" else "negative"
            val label = if (tookAction) currentBanner.positiveLabel else currentBanner.negativeLabel
            firebase.logEvent(
                R.string.event_banner,
                R.string.param_type to currentBanner.eventType,
                R.string.param_action to action,
                R.string.param_label to label,
            )
            updateBannerState()
        }
    }

    private suspend fun pendingBlogPost(): Banner.BlogAnnouncement? {
        val json = tasksPreferences.get(TasksPreferences.blogPendingPost, "")
        if (json.isBlank()) return null
        return try {
            Banner.BlogAnnouncement(Json.decodeFromString<BlogPost>(json))
        } catch (_: Exception) {
            null
        }
    }

    private fun CaldavAccount.isSubscriptionRequired() =
        (!inventory.hasPro && needsPro) || (isTasksOrg && isPaymentRequired())
}
