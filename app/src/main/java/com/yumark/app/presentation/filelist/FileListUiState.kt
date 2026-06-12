package com.yumark.app.presentation.filelist

import com.yumark.app.domain.model.*

sealed class FileListUiState {
    data object Loading : FileListUiState()
    data class Success(
        val documents: List<Document>,
        val folders: List<Folder>,
        val folderTree: List<FolderTreeNode>?,
        val currentFolderId: String?,
        val searchResults: List<SearchResult>,
        val isSearching: Boolean,
        val sortOption: SortOption
    ) : FileListUiState()
}
