package org.tasks.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tasks.billing.PurchaseState
import org.tasks.caldav.metadata.TagMetadataSync
import org.tasks.compose.drawer.DrawerItem
import org.tasks.compose.throttleLatest
import org.tasks.data.NO_COUNT
import org.tasks.data.count
import org.tasks.data.dao.CaldavDao
import org.tasks.data.dao.FilterDao
import org.tasks.data.dao.LocationDao
import org.tasks.data.dao.TagDataDao
import org.tasks.data.dao.TaskDao
import org.tasks.filters.AlphanumComparator
import org.tasks.filters.CaldavFilter
import org.tasks.filters.CustomFilter
import org.tasks.filters.DrawerSortMode
import org.tasks.filters.Filter
import org.tasks.filters.FilterProvider
import org.tasks.filters.NavigationDrawerSubheader
import org.tasks.filters.PlaceFilter
import org.tasks.filters.SignInPrompt
import org.tasks.filters.TagFilter
import org.tasks.filters.localizedStringComparator
import org.tasks.preferences.TasksPreferences
import org.tasks.kmp.org.tasks.themes.ColorProvider
import org.tasks.sync.SyncAdapters
import org.tasks.sync.SyncSource

private const val TAG = "DrawerViewModel"

private val SEARCH_NAME =
    AlphanumComparator(DrawerItem.Filter::title, localizedStringComparator())

open class DrawerViewModel(
    private val filterProvider: FilterProvider,
    private val taskDao: TaskDao,
    private val caldavDao: CaldavDao,
    private val tagDataDao: TagDataDao,
    private val filterDao: FilterDao,
    private val locationDao: LocationDao,
    private val tagMetadataSync: TagMetadataSync,
    private val syncAdapters: SyncAdapters,
    private val tasksPreferences: TasksPreferences,
    private val purchaseState: PurchaseState,
    refreshFlow: Flow<Unit> = emptyFlow(),
) : ViewModel() {

    data class State(
        val drawerItems: ImmutableList<DrawerItem> = persistentListOf(),
        val searchItems: ImmutableList<DrawerItem> = persistentListOf(),
        val menuQuery: String = "",
        val selectedFilter: Filter? = null,
        val sortMode: DrawerSortMode = DrawerSortMode.MANUAL,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        updateFilters()

        refreshFlow
            .throttleLatest(1000)
            .onEach { updateFilters() }
            .launchIn(viewModelScope)
    }

    fun setSelectedFilter(filter: Filter?) {
        _state.update { it.copy(selectedFilter = filter) }
        updateFilters()
    }

    fun updateFilters() {
        viewModelScope.launch(Dispatchers.IO) {
            // Nothing above catches this: viewModelScope's SupervisorJob routes a child failure to
            // the default handler, which on Android takes the process down - over a drawer that
            // could not be listed.
            try {
                val selectedFilter = _state.value.selectedFilter
                val sortMode = DrawerSortMode.fromValue(
                    tasksPreferences.get(TasksPreferences.drawerSortMode, DrawerSortMode.MANUAL.name)
                )
                if (sortMode != _state.value.sortMode) {
                    _state.update { it.copy(sortMode = sortMode) }
                }
                filterProvider
                    .drawerItems(sortMode)
                    .map { item -> item.toDrawerItem(selectedFilter) }
                    .let { filters ->
                        _state.update { it.copy(drawerItems = filters.toPersistentList()) }
                    }
                val query = _state.value.menuQuery
                if (query.isNotBlank()) {
                    updateSearch(query, selectedFilter, sortMode)
                }
            } catch (e: CancellationException) {
                // A RuntimeException on the JVM, so the catch below would log a cancelled scope -
                // signing out, or the activity going away - as a failure.
                throw e
            } catch (e: Exception) {
                Logger.e(e, tag = TAG) { "Failed to update filters" }
            }
        }
    }

    fun setSortMode(sortMode: DrawerSortMode) {
        if (sortMode == _state.value.sortMode) {
            return
        }
        _state.update { it.copy(sortMode = sortMode) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                tasksPreferences.set(TasksPreferences.drawerSortMode, sortMode.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, tag = TAG) { "Failed to persist drawer sort mode" }
            }
            updateFilters()
        }
    }

    /**
     * Persists a manual reorder coming from a drag inside the drawer. [ordered] holds the dragged
     * group - one account's lists, or every tag, place or custom filter - in its new visual order;
     * positions are written only for that group.
     */
    fun setManualOrder(ordered: List<Filter>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var tagsMoved = false
                ordered.forEachIndexed { order, filter ->
                    when (filter) {
                        is CaldavFilter -> caldavDao.setOrder(filter.calendar.id, order)
                        is TagFilter -> {
                            filter.tagData.id?.let { tagDataDao.setOrder(it, order) }
                            tagsMoved = true
                        }
                        is CustomFilter -> filterDao.setOrder(filter.id, order)
                        is PlaceFilter -> locationDao.setOrder(filter.place.id, order)
                    }
                }
                if (tagsMoved) {
                    // Tag order is metadata that syncs to other devices, unlike list order.
                    tagMetadataSync.markOrderDirty()
                    syncAdapters.sync(SyncSource.METADATA_CHANGE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, tag = TAG) { "Failed to persist drawer order" }
            }
            updateFilters()
        }
    }

    fun setMenuQuery(query: String) {
        _state.update { it.copy(menuQuery = query) }
        if (query.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                updateSearch(query, _state.value.selectedFilter, _state.value.sortMode)
            }
        }
    }

    private suspend fun updateSearch(
        query: String,
        selectedFilter: Filter?,
        sortMode: DrawerSortMode,
    ) {
        val items = filterProvider
            .allFilters()
            .filter { it.title.contains(query, ignoreCase = true) }
            .map { item ->
                DrawerItem.Filter(
                    title = item.title,
                    icon = item.resolveIcon(purchaseState),
                    color = if (purchaseState.purchasedThemes() || ColorProvider.isFreeColor(item.tint)) item.tint else 0,
                    adjustColor = item.tint != 0,
                    count = item.count.takeIf { it != NO_COUNT } ?: try {
                        taskDao.count(item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(e, tag = TAG) { "Failed to count tasks" }
                        0
                    },
                    selected = selectedFilter?.let { item.areItemsTheSame(it) } ?: false,
                    shareCount = if (item is CaldavFilter) item.principals else 0,
                    filter = item,
                )
            }
        val sorted = when (sortMode) {
            // Manual order is per section; a flat search result list keeps the grouped order
            // that callers already get instead of interleaving sections by their stored order.
            DrawerSortMode.MANUAL -> items
            DrawerSortMode.NAME -> items.sortedWith(SEARCH_NAME)
        }
        _state.update { it.copy(searchItems = sorted.toPersistentList()) }
    }

    fun toggleCollapsed(subheader: NavigationDrawerSubheader) {
        viewModelScope.launch(Dispatchers.IO) {
            toggleCollapsed(subheader, caldavDao, tasksPreferences)
            updateFilters()
        }
    }

    private suspend fun org.tasks.filters.FilterListItem.toDrawerItem(
        selectedFilter: Filter?,
    ): DrawerItem = when (this) {
        is Filter -> DrawerItem.Filter(
            title = title,
            icon = resolveIcon(purchaseState),
            color = if (purchaseState.purchasedThemes() || ColorProvider.isFreeColor(tint)) tint else 0,
            adjustColor = tint != 0,
            count = count.takeIf { it != NO_COUNT } ?: try {
                taskDao.count(this)
            } catch (e: Exception) {
                Logger.e(e, tag = TAG) { "Failed to count tasks" }
                0
            },
            selected = selectedFilter?.let { areItemsTheSame(it) } ?: false,
            shareCount = if (this is CaldavFilter) principals else 0,
            filter = this,
        )
        is NavigationDrawerSubheader -> DrawerItem.Header(
            title = title ?: "",
            collapsed = isCollapsed,
            hasError = error,
            canAdd = addIntentRc != 0,
            hasChildren = childCount > 0,
            openTaskApp = openTaskApp,
            header = this,
        )
        is SignInPrompt -> DrawerItem.SignIn
        else -> throw IllegalArgumentException()
    }
}
