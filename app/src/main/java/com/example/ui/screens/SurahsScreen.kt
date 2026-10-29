package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Surah
import com.example.ui.components.SurahItemCard
import com.example.ui.theme.GoldAccent
import com.example.ui.viewmodel.QuranViewModel

@Composable
fun SurahsScreen(
    viewModel: QuranViewModel,
    onSurahSelected: (Surah) -> Unit,
    onOpenAddBookmark: (Surah) -> Unit,
    modifier: Modifier = Modifier
) {
    val displayedSurahs by viewModel.displayedSurahs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val showFavoritesOnly by viewModel.showFavoritesOnly.collectAsState()
    val favoriteNumbers by viewModel.favoriteSurahNumbers.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val currentReciter = playerState.currentReciter

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Search Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("surah_search_input"),
                placeholder = {
                    Text(
                        text = "ابحث باسم السورة أو رقمها (مثال: الكهف أو ١٨)…",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "بحث",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.onSearchQueryChanged("") },
                            modifier = Modifier.testTag("clear_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "مسح البحث"
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Filter Chips: All vs Favorites
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !showFavoritesOnly,
                    onClick = { if (showFavoritesOnly) viewModel.toggleFavoritesOnly() },
                    label = { Text("جميع السور (114)") },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("filter_all_surahs")
                )

                FilterChip(
                    selected = showFavoritesOnly,
                    onClick = { if (!showFavoritesOnly) viewModel.toggleFavoritesOnly() },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("المفضلة (${favoriteNumbers.size})")
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFE11D48),
                        selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.testTag("filter_favorite_surahs")
                )
            }
        }

        // Surahs List
        if (displayedSurahs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (showFavoritesOnly) "لا توجد سور في قائمة المفضلة بعد" else "لا توجد نتائج مطابقة لبحثك",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (showFavoritesOnly) "اضغط على أيقونة القلب بجانب أي سورة لإضافتها إلى المفضلة" else "تأكد من كتابة اسم السورة أو رقمها بصورة صحيحة",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("surahs_lazy_column"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayedSurahs, key = { it.id }) { surah ->
                    val isCurrent = playerState.currentSurah?.id == surah.id
                    val isPlaying = isCurrent && playerState.isPlaying
                    val isFav = favoriteNumbers.contains(surah.id)
                    val isDownloaded = viewModel.isSurahDownloaded(surah.id)
                    val isAvailable = currentReciter?.isSurahAvailable(surah.id) ?: true

                    SurahItemCard(
                        surah = surah,
                        isPlaying = isPlaying,
                        isCurrent = isCurrent,
                        isFavorite = isFav,
                        isDownloaded = isDownloaded,
                        isAvailableForReciter = isAvailable,
                        onCardClick = { onSurahSelected(surah) },
                        onPlayClick = {
                            if (isCurrent) {
                                viewModel.togglePlayPause()
                            } else {
                                onSurahSelected(surah)
                            }
                        },
                        onFavoriteClick = { viewModel.toggleFavorite(surah.id) },
                        onDownloadClick = { viewModel.startDownload(surah) },
                        onBookmarkClick = { onOpenAddBookmark(surah) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp)) // Extra space for mini player
                }
            }
        }
    }
}
