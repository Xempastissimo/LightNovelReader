package com.xempastissimo.lightnovelreader.ui.screen.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xempastissimo.lightnovelreader.data.network.HttpFailure
import com.xempastissimo.lightnovelreader.data.repo.BookRepository
import com.xempastissimo.lightnovelreader.data.repo.ShelfRepository
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.SearchField
import com.xempastissimo.lightnovelreader.ui.AppContainer
import com.xempastissimo.lightnovelreader.ui.AppViewModelFactory
import com.xempastissimo.lightnovelreader.ui.component.BookRow
import com.xempastissimo.lightnovelreader.ui.component.EmptyBox
import com.xempastissimo.lightnovelreader.ui.component.LoadingBox
import com.xempastissimo.lightnovelreader.ui.component.StateCrossfade
import com.xempastissimo.lightnovelreader.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val keyword: String = "",
    val field: SearchField = SearchField.TITLE,
    val results: List<Book> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val requiresLogin: Boolean = false,
    val history: List<String> = emptyList(),
) {
    val hasSearched: Boolean get() = results.isNotEmpty() || error != null
}

/** Which of the search screen's mutually exclusive bodies is on show. */
private enum class SearchPhase { SEARCHING, LOGIN, ERROR, RESULTS, HISTORY }

class SearchViewModel(
    private val repository: BookRepository,
    private val shelfRepository: ShelfRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            shelfRepository.searchHistory.collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
    }

    fun onKeywordChange(value: String) = _state.update { it.copy(keyword = value) }

    fun onFieldChange(field: SearchField) = _state.update { it.copy(field = field) }

    fun clearHistory() {
        viewModelScope.launch { shelfRepository.clearSearchHistory() }
    }

    fun search(keyword: String = _state.value.keyword) {
        val target = keyword.trim()
        if (target.isEmpty()) return
        _state.update { it.copy(keyword = target, searching = true, error = null, requiresLogin = false) }
        viewModelScope.launch {
            runCatching { repository.search(target, _state.value.field) }
                .onSuccess { books ->
                    shelfRepository.recordSearch(target)
                    _state.update { it.copy(results = books, searching = false, error = null) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            results = emptyList(),
                            searching = false,
                            error = error.toUserMessage(),
                            requiresLogin = error is HttpFailure.AuthRequired,
                        )
                    }
                }
        }
    }

    companion object {
        fun factory(container: AppContainer) = AppViewModelFactory<SearchViewModel> {
            SearchViewModel(it.bookRepository, it.shelfRepository)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenBook: (Book) -> Unit,
    viewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.factory(com.xempastissimo.lightnovelreader.ui.LocalAppContainer.current),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("搜索") })

        OutlinedTextField(
            value = state.keyword,
            onValueChange = viewModel::onKeywordChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            label = { Text(if (state.field == SearchField.TITLE) "小说标题" else "作者名称") },
            singleLine = true,
            trailingIcon = {
                Row {
                    if (state.keyword.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onKeywordChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "清空")
                        }
                    }
                    IconButton(onClick = { viewModel.search() }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
        )

        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField.entries.forEach { field ->
                FilterChip(
                    selected = state.field == field,
                    onClick = { viewModel.onFieldChange(field) },
                    label = { Text(if (field == SearchField.TITLE) "按标题" else "按作者") },
                )
            }
        }

        val phase = when {
            state.searching -> SearchPhase.SEARCHING
            state.requiresLogin -> SearchPhase.LOGIN
            state.error != null -> SearchPhase.ERROR
            state.results.isNotEmpty() -> SearchPhase.RESULTS
            else -> SearchPhase.HISTORY
        }

        // Results fade in over the suggestions instead of replacing them outright,
        // and the spinner fades into the list rather than being yanked away.
        StateCrossfade(
            targetState = phase,
            label = "search-body",
            modifier = Modifier.weight(1f),
        ) { body ->
            when (body) {
                SearchPhase.SEARCHING -> LoadingBox(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 60.dp),
                    label = "搜索中…",
                )

                SearchPhase.LOGIN -> EmptyBox(
                    title = "搜索需要登录",
                    hint = "该站点的搜索功能要求登录后才能使用，请到「设置 → 账号」登录",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 60.dp),
                )

                SearchPhase.ERROR -> EmptyBox(
                    title = "搜索失败",
                    hint = state.error,
                    actionLabel = "重试",
                    onAction = { viewModel.search() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 60.dp),
                )

                SearchPhase.RESULTS -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(state.results, key = { it.bookId }) { book ->
                        BookRow(
                            book = book,
                            onClick = { onOpenBook(book) },
                            // A second search re-ranks the list; the rows that stayed
                            // put glide to their new place instead of jumping.
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                SearchPhase.HISTORY -> Column(modifier = Modifier.fillMaxSize()) {
                    if (state.history.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("搜索历史", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "清空",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            state.history.forEach { keyword ->
                                AssistChip(
                                    onClick = { viewModel.search(keyword) },
                                    label = { Text(keyword) },
                                )
                            }
                        }
                    } else {
                        EmptyBox(
                            title = "搜索轻小说",
                            hint = "支持按标题或作者搜索；也可直接在书架页输入书籍 ID",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 60.dp),
                        )
                    }
                }
            }
        }
    }
}
