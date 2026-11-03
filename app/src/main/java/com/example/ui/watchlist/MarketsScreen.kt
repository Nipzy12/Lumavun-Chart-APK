package com.example.ui.watchlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.example.analysis.events.EventImpactLevel
import com.example.analysis.events.EventRiskAssessment
import com.example.analysis.events.EventRiskManager
import com.example.market.calendar.BiasDirection
import com.example.market.calendar.CalendarImpactFilter
import com.example.market.calendar.CalendarLoadState
import com.example.market.calendar.CalendarTimeView
import com.example.market.calendar.CurrencyPairBiasEngine
import com.example.market.calendar.EconomicCalendarEvent
import com.example.market.calendar.EconomicCalendarService
import com.example.storage.preferences.CalendarTimezoneOption
import com.example.market.candle.InstrumentCategory
import com.example.market.candle.MarketProvider
import com.example.market.candle.Ticker24h
import com.example.market.candle.formatCompactVolume
import com.example.market.candle.formatCryptoPrice
import com.example.market.candle.formatInstrumentPrice
import com.example.market.candle.formatPriceWithPrefix
import com.example.market.candle.formatSymbolPair
import com.example.market.candle.inferInstrumentCategory
import com.example.market.candle.inferMarketProvider
import com.example.market.rest.ExchangeSymbolInfo
import com.example.market.status.MarketStatusEvaluation
import com.example.market.status.MarketStatusService
import com.example.market.symbols.SymbolRepository
import com.example.market.websocket.WsConnectionStatus
import com.example.storage.watchlist.WatchlistEntity
import com.example.ui.components.MarketStatusLedDot
import com.example.ui.components.ledColorFor
import com.example.ui.components.ledSoftBgFor
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BearishRedSoft
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.BullishGreenSoft
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NeutralAmber
import com.example.ui.theme.NeutralAmberSoft
import java.util.Locale

enum class MarketsSubTab(val label: String) {
    WATCHLIST("Watchlist"),
    CALENDAR("Calendar")
}

enum class MarketDiscoveryCategory(val label: String) {
    WATCHLIST("Watchlist"),
    FAVORITES("★ Favorites"),
    ALL_USDT("All USDT"),
    TOP_VOLUME("Top Volume"),
    TOP_GAINERS("Top Gainers"),
    TOP_LOSERS("Top Losers")
}

/**
 * Dedicated TradingView-inspired Watchlist & Market Discovery Screen (Tab 1: Watchlist).
 * - Responsive two-area row layout (Left: Symbol + ACTIVE badge + 24h Volume; Right: Monospace Price + 24h Change% + compact actions).
 * - Single-active-row swipe-to-delete with full right-side Delete button and Undo banner.
 * - Dynamic Binance USDT pair search & validation with duplicate prevention and clear status feedback.
 */
@Composable
fun MarketsScreen(
    activeSymbol: String,
    watchlist: List<WatchlistEntity>,
    liveTickers: Map<String, Ticker24h>,
    favoriteSymbols: Set<String> = emptySet(),
    searchQuery: String,
    searchResults: List<ExchangeSymbolInfo>,
    selectedCategory: InstrumentCategory = InstrumentCategory.ALL,
    onSelectCategory: (InstrumentCategory) -> Unit = {},
    calendarState: CalendarLoadState = CalendarLoadState.Loading,
    eventRiskAssessment: EventRiskAssessment = EventRiskAssessment(),
    isLoadingWatchlistData: Boolean = false,
    feedbackMessage: String? = null,
    isFeedbackError: Boolean = false,
    onDismissFeedback: () -> Unit = {},
    onRefreshWatchlist: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onSelectSymbolForChart: (String) -> Unit,
    onAddPair: (String) -> Unit,
    onRemovePair: (String) -> Unit,
    onMovePairUp: (String) -> Unit,
    onMovePairDown: (String) -> Unit,
    onToggleFavorite: (String) -> Unit = {},
    onRefreshCalendar: () -> Unit = {},
    binanceConnectionStatus: WsConnectionStatus = WsConnectionStatus.LIVE,
    biQuoteConnectionStatus: WsConnectionStatus = WsConnectionStatus.LIVE,
    hasFeedError: Boolean = false,
    modifier: Modifier = Modifier
) {
    var selectedDiscoveryCategory by remember { mutableStateOf(MarketDiscoveryCategory.WATCHLIST) }
    var recentlyDeletedSymbol by remember { mutableStateOf<String?>(null) }
    var showAddSymbolQuickList by remember { mutableStateOf(false) }
    // Requirement B: Restrict swipe-to-delete to one active row at a time
    var activeSwipedSymbol by remember { mutableStateOf<String?>(null) }
    val searchFocusRequester = remember { FocusRequester() }

    val watchlistSymbolsSet = remember(watchlist) {
        watchlist.map { it.symbol.uppercase(Locale.US) }.toSet()
    }

    val filteredWatchlist = remember(watchlist, selectedCategory) {
        if (selectedCategory == InstrumentCategory.ALL) {
            watchlist
        } else {
            watchlist.filter { item ->
                val cat = if (item.category.isNotBlank()) {
                    InstrumentCategory.fromCode(item.category)
                } else {
                    inferInstrumentCategory(item.symbol)
                }
                cat == selectedCategory
            }
        }
    }

    // Close swiped row if it was removed from watchlist
    LaunchedEffect(watchlistSymbolsSet) {
        if (activeSwipedSymbol != null && activeSwipedSymbol !in watchlistSymbolsSet) {
            activeSwipedSymbol = null
        }
    }

    fun handleDeleteWithUndo(symbol: String) {
        if (activeSwipedSymbol.equals(symbol, ignoreCase = true)) {
            activeSwipedSymbol = null
        }
        recentlyDeletedSymbol = symbol
        onRemovePair(symbol)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("markets_screen")
    ) {
        // TradingView-style Watchlist Header with Refresh & "+ Symbol" buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WATCHLIST",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "${watchlist.size} instruments · Crypto, Forex, Metals, Stocks & Indices",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .clickable { onRefreshWatchlist() }
                        .testTag("watchlist_refresh_button")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoadingWatchlistData) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = CyanAccent
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Watchlist Data",
                                tint = CyanAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Surface(
                    color = CyanAccent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .border(1.dp, CyanAccent.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .clickable {
                            showAddSymbolQuickList = !showAddSymbolQuickList
                            activeSwipedSymbol = null
                            if (showAddSymbolQuickList) {
                                runCatching { searchFocusRequester.requestFocus() }
                            }
                        }
                        .testTag("watchlist_add_symbol_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (showAddSymbolQuickList) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = "Add Symbol",
                            tint = CyanAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (showAddSymbolQuickList) "Close Add" else "+ Symbol",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Feedback Banner for Add / Duplicate / Invalid Symbol / API Error / Loading
        AnimatedVisibility(visible = !feedbackMessage.isNullOrBlank()) {
            val msg = feedbackMessage.orEmpty()
            val bannerColor = if (isFeedbackError) BearishRed else CyanAccent
            val bannerBg = if (isFeedbackError) BearishRedSoft else CyanAccent.copy(alpha = 0.14f)
            Surface(
                color = bannerBg,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .border(1.dp, bannerColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .testTag("watchlist_feedback_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFeedbackError) BearishRed else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("watchlist_feedback_text")
                    )
                    IconButton(
                        onClick = onDismissFeedback,
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("watchlist_feedback_dismiss")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss Feedback",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }

        // Undo Banner when a symbol is removed from the Watchlist
        AnimatedVisibility(visible = recentlyDeletedSymbol != null) {
            val deletedSym = recentlyDeletedSymbol ?: ""
            Surface(
                color = NeutralAmberSoft,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .border(1.dp, NeutralAmber.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .testTag("watchlist_undo_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatSymbolPair(deletedSym)} removed from Watchlist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = CyanAccent,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .clickable {
                                    if (deletedSym.isNotBlank()) {
                                        onAddPair(deletedSym)
                                    }
                                    recentlyDeletedSymbol = null
                                }
                                .testTag("watchlist_undo_delete_button")
                        ) {
                            Text(
                                text = "UNDO",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF001F26),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        IconButton(
                            onClick = { recentlyDeletedSymbol = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss Undo",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Dynamic Pair Search & Direct Add Row (Requirement C)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    activeSwipedSymbol = null
                    onSearchQueryChange(it)
                },
                placeholder = {
                    Text(
                        text = "Search Crypto, Forex, Gold, Stocks (BTC, EURUSD, XAUUSD, AAPL)...",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Pair",
                        tint = CyanAccent
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.testTag("pair_search_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear Search"
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (searchQuery.isNotBlank()) {
                            onAddPair(searchQuery)
                        }
                    }
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocusRequester)
                    .testTag("pair_search_input")
            )

            if (searchQuery.isNotBlank()) {
                Surface(
                    color = CyanAccent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .clickable { onAddPair(searchQuery) }
                        .testTag("pair_search_submit_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Searched Symbol",
                            tint = Color(0xFF001F26),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Add",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFF001F26),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Multi-Market Instrument Category Tabs (All, Crypto, Forex, Metals, Stocks, Indices)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("watchlist_category_tabs_row"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InstrumentCategory.entries.forEach { cat ->
                val isSelectedCat = selectedCategory == cat
                Surface(
                    color = if (isSelectedCat) CyanAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (isSelectedCat) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            activeSwipedSymbol = null
                            onSelectCategory(cat)
                        }
                        .testTag("watchlist_category_${cat.name.lowercase()}")
                ) {
                    Text(
                        text = cat.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelectedCat) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelectedCat) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (searchQuery.isNotBlank() || showAddSymbolQuickList) {
            // Search Results from Multi-Market Catalog (Binance USDT + BiQuote Forex/Metals/Stocks/Indices)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val headerLabel = if (selectedCategory == InstrumentCategory.ALL) {
                    "MULTI-MARKET INSTRUMENTS (${searchResults.size})"
                } else {
                    "${selectedCategory.label.uppercase(Locale.US)} INSTRUMENTS (${searchResults.size})"
                }
                Text(
                    text = headerLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "Tap + to add · Tap row to open chart",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (searchResults.isEmpty() && searchQuery.isNotBlank()) {
                val normalizedCandidate = SymbolRepository.normalizeMultiMarketSymbolInput(searchQuery, selectedCategory)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(16.dp)
                        .testTag("pair_search_empty_state")
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (normalizedCandidate.isNotEmpty()) {
                            Text(
                                text = "${formatSymbolPair(normalizedCandidate)} belum ada di cache lokal",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tekan tombol di bawah atau tombol Add untuk memvalidasi langsung dari katalog Binance / BiQuote.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { onAddPair(normalizedCandidate) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyanAccent,
                                    contentColor = Color(0xFF001F26)
                                ),
                                modifier = Modifier.testTag("validate_custom_pair_button")
                            ) {
                                Text(
                                    text = "Validasi & Tambah ${formatSymbolPair(normalizedCandidate)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Text(
                                text = "Symbol '$searchQuery' tidak valid. Gunakan format simbol yang benar (contoh: BTCUSDT, EURUSD, XAUUSD, AAPL).",
                                style = MaterialTheme.typography.bodySmall,
                                color = BearishRed,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("pair_search_results_list")
                ) {
                    items(searchResults, key = { it.symbol }) { item ->
                        val inWatchlist = watchlistSymbolsSet.contains(item.symbol.uppercase(Locale.US))
                        val isFav = favoriteSymbols.contains(item.symbol.uppercase(Locale.US))
                        val liveTicker = liveTickers[item.symbol]
                        val connStatus = if (item.provider == MarketProvider.BIQUOTE) {
                            biQuoteConnectionStatus
                        } else {
                            binanceConnectionStatus
                        }
                        val itemMarketStatus = MarketStatusService.evaluate(
                            symbol = item.symbol,
                            category = item.category,
                            provider = item.provider,
                            ticker = liveTicker,
                            connectionStatus = connStatus,
                            hasFeedError = hasFeedError
                        )
                        SearchSymbolResultRow(
                            item = item,
                            inWatchlist = inWatchlist,
                            isFavorite = isFav,
                            liveTicker = liveTicker,
                            marketStatus = itemMarketStatus,
                            onSelectPair = {
                                if (!inWatchlist) {
                                    onAddPair(item.symbol)
                                }
                                showAddSymbolQuickList = false
                                onSelectSymbolForChart(item.symbol)
                            },
                            onToggleFavorite = { onToggleFavorite(item.symbol) },
                            onToggleWatchlist = {
                                if (inWatchlist) {
                                    handleDeleteWithUndo(item.symbol)
                                } else {
                                    onAddPair(item.symbol)
                                }
                            }
                        )
                    }
                }
            }
        } else {
            // Market Discovery Filter Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MarketDiscoveryCategory.entries.forEach { cat ->
                    val isSel = selectedDiscoveryCategory == cat
                    Surface(
                        color = if (isSel) CyanAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (isSel) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                activeSwipedSymbol = null
                                selectedDiscoveryCategory = cat
                            }
                            .testTag("market_filter_${cat.name.lowercase()}")
                    ) {
                        Text(
                            text = cat.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSel) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Column headers (TradingView Watchlist table style: LEFT = SYMBOL / 24H VOL | RIGHT = LAST PRICE / 24H CHG%)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SYMBOL / 24H VOL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "LAST PRICE / 24H CHG%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (selectedDiscoveryCategory == MarketDiscoveryCategory.WATCHLIST) {
                if (filteredWatchlist.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(24.dp)
                            .testTag("watchlist_empty_state"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (selectedCategory == InstrumentCategory.ALL) {
                                    "Watchlist is empty"
                                } else {
                                    "No ${selectedCategory.label} instruments in Watchlist"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tap '+ Symbol' or use the search bar above to add Crypto, Forex, Metals, Stocks, or Indices.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("watchlist_lazy_column")
                    ) {
                        itemsIndexed(filteredWatchlist, key = { _, item -> item.symbol }) { index, item ->
                            val live = liveTickers[item.symbol]
                            val price = if (live != null && live.lastPrice > 0.0) live.lastPrice else item.lastPrice
                            val changePct = live?.priceChangePercent ?: item.priceChangePercent
                            val volume = if (live != null && live.quoteVolume > 0.0) live.quoteVolume else item.volume
                            val isSelected = item.symbol.equals(activeSymbol, ignoreCase = true)
                            val isRowSwipedOpen = activeSwipedSymbol.equals(item.symbol, ignoreCase = true)
                            val itemCategory = live?.category ?: if (item.category.isNotBlank()) {
                                InstrumentCategory.fromCode(item.category)
                            } else {
                                inferInstrumentCategory(item.symbol)
                            }
                            val itemProvider = live?.provider ?: if (item.provider.isNotBlank()) {
                                MarketProvider.fromCode(item.provider)
                            } else {
                                inferMarketProvider(item.symbol, itemCategory)
                            }
                            val itemDisplayName = live?.displayName?.ifBlank { item.displayName } ?: item.displayName
                            val itemConnStatus = if (itemProvider == MarketProvider.BIQUOTE) {
                                biQuoteConnectionStatus
                            } else {
                                binanceConnectionStatus
                            }
                            val fallbackQuoteTs = if (price > 0.0 && item.updatedAt > 0L) item.updatedAt else null
                            val rowMarketStatus = MarketStatusService.evaluate(
                                symbol = item.symbol,
                                category = itemCategory,
                                provider = itemProvider,
                                ticker = live,
                                fallbackQuoteTimestampMs = fallbackQuoteTs,
                                connectionStatus = itemConnStatus,
                                hasFeedError = hasFeedError
                            )

                            WatchlistRowCard(
                                symbol = item.symbol,
                                price = price,
                                changePercent = changePct,
                                quoteVolume = volume,
                                category = itemCategory,
                                provider = itemProvider,
                                displayName = itemDisplayName,
                                bidPrice = live?.bidPrice ?: 0.0,
                                askPrice = live?.askPrice ?: 0.0,
                                marketStatus = rowMarketStatus,
                                isSelected = isSelected,
                                isFavorite = favoriteSymbols.contains(item.symbol.uppercase(Locale.US)),
                                canMoveUp = index > 0,
                                canMoveDown = index < filteredWatchlist.lastIndex,
                                canRemove = true,
                                isSwipeOpen = isRowSwipedOpen,
                                onSwipeOpenChange = { shouldOpen ->
                                    activeSwipedSymbol = if (shouldOpen) {
                                        item.symbol
                                    } else if (activeSwipedSymbol.equals(item.symbol, ignoreCase = true)) {
                                        null
                                    } else {
                                        activeSwipedSymbol
                                    }
                                },
                                onSelect = {
                                    activeSwipedSymbol = null
                                    onSelectSymbolForChart(item.symbol)
                                },
                                onToggleFavorite = { onToggleFavorite(item.symbol) },
                                onMoveUp = {
                                    activeSwipedSymbol = null
                                    onMovePairUp(item.symbol)
                                },
                                onMoveDown = {
                                    activeSwipedSymbol = null
                                    onMovePairDown(item.symbol)
                                },
                                onRemove = { handleDeleteWithUndo(item.symbol) }
                            )
                        }
                    }
                }
            } else {
                val dynamicList = remember(liveTickers, watchlist, favoriteSymbols, selectedDiscoveryCategory, selectedCategory) {
                    val baseTickers = liveTickers.values.toList().ifEmpty {
                        watchlist.map {
                            val cat = if (it.category.isNotBlank()) InstrumentCategory.fromCode(it.category) else inferInstrumentCategory(it.symbol)
                            val prov = if (it.provider.isNotBlank()) MarketProvider.fromCode(it.provider) else inferMarketProvider(it.symbol, cat)
                            Ticker24h(
                                symbol = it.symbol,
                                lastPrice = it.lastPrice,
                                priceChangePercent = it.priceChangePercent,
                                highPrice = it.lastPrice,
                                lowPrice = it.lastPrice,
                                volume = it.volume,
                                quoteVolume = it.volume,
                                category = cat,
                                provider = prov,
                                displayName = it.displayName
                            )
                        }
                    }.let { list ->
                        if (selectedCategory == InstrumentCategory.ALL) list
                        else list.filter { it.category == selectedCategory }
                    }
                    when (selectedDiscoveryCategory) {
                        MarketDiscoveryCategory.FAVORITES ->
                            baseTickers.filter { favoriteSymbols.contains(it.symbol.uppercase(Locale.US)) }
                        MarketDiscoveryCategory.ALL_USDT ->
                            baseTickers.sortedByDescending { it.quoteVolume }
                        MarketDiscoveryCategory.TOP_VOLUME ->
                            baseTickers.sortedByDescending { it.quoteVolume }.take(40)
                        MarketDiscoveryCategory.TOP_GAINERS ->
                            baseTickers.sortedByDescending { it.priceChangePercent }.take(40)
                        MarketDiscoveryCategory.TOP_LOSERS ->
                            baseTickers.sortedBy { it.priceChangePercent }.take(40)
                        MarketDiscoveryCategory.WATCHLIST -> baseTickers
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("discovery_lazy_column")
                ) {
                    items(dynamicList, key = { it.symbol }) { ticker ->
                        val inWatchlist = watchlistSymbolsSet.contains(ticker.symbol.uppercase(Locale.US))
                        val isFav = favoriteSymbols.contains(ticker.symbol.uppercase(Locale.US))
                        val isSelected = ticker.symbol.equals(activeSymbol, ignoreCase = true)
                        val isRowSwipedOpen = activeSwipedSymbol.equals(ticker.symbol, ignoreCase = true)
                        val itemConnStatus = if (ticker.provider == MarketProvider.BIQUOTE) {
                            biQuoteConnectionStatus
                        } else {
                            binanceConnectionStatus
                        }
                        val tickerMarketStatus = MarketStatusService.evaluate(
                            symbol = ticker.symbol,
                            category = ticker.category,
                            provider = ticker.provider,
                            ticker = ticker,
                            connectionStatus = itemConnStatus,
                            hasFeedError = hasFeedError
                        )

                        WatchlistRowCard(
                            symbol = ticker.symbol,
                            price = ticker.lastPrice,
                            changePercent = ticker.priceChangePercent,
                            quoteVolume = ticker.quoteVolume,
                            category = ticker.category,
                            provider = ticker.provider,
                            displayName = ticker.displayName,
                            bidPrice = ticker.bidPrice,
                            askPrice = ticker.askPrice,
                            marketStatus = tickerMarketStatus,
                            isSelected = isSelected,
                            isFavorite = isFav,
                            canMoveUp = false,
                            canMoveDown = false,
                            canRemove = inWatchlist,
                            showAddButton = !inWatchlist,
                            isSwipeOpen = isRowSwipedOpen,
                            onSwipeOpenChange = { shouldOpen ->
                                activeSwipedSymbol = if (shouldOpen) {
                                    ticker.symbol
                                } else if (activeSwipedSymbol.equals(ticker.symbol, ignoreCase = true)) {
                                    null
                                } else {
                                    activeSwipedSymbol
                                }
                            },
                            onSelect = {
                                activeSwipedSymbol = null
                                if (!inWatchlist) onAddPair(ticker.symbol)
                                onSelectSymbolForChart(ticker.symbol)
                            },
                            onToggleFavorite = { onToggleFavorite(ticker.symbol) },
                            onAdd = { onAddPair(ticker.symbol) },
                            onMoveUp = {},
                            onMoveDown = {},
                            onRemove = { handleDeleteWithUndo(ticker.symbol) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSymbolResultRow(
    item: ExchangeSymbolInfo,
    inWatchlist: Boolean,
    isFavorite: Boolean,
    liveTicker: Ticker24h?,
    marketStatus: MarketStatusEvaluation = MarketStatusService.evaluate(
        symbol = item.symbol,
        category = item.category,
        provider = item.provider,
        ticker = liveTicker
    ),
    onSelectPair: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatchlist: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (inWatchlist) CyanAccent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                RoundedCornerShape(10.dp)
            )
            .clickable { onSelectPair() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("search_result_${item.symbol}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Area: Symbol + Market / Trading Status
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MarketStatusLedDot(
                    status = marketStatus,
                    dotSize = 7.dp,
                    testTag = "search_led_${item.symbol}"
                )
                Text(
                    text = formatSymbolPair(item.symbol, item.category),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Surface(
                    color = CyanAccent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = item.category.shortBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                if (inWatchlist) {
                    Surface(
                        color = BullishGreenSoft,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.widthIn(min = 44.dp)
                    ) {
                        Text(
                            text = "SAVED",
                            style = MaterialTheme.typography.labelSmall,
                            color = BullishGreen,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            val subText = when {
                liveTicker != null && liveTicker.quoteVolume > 0.0 ->
                    "Vol $${formatCompactVolume(liveTicker.quoteVolume)} · ${item.provider.displayLabel}"
                item.displayName.isNotBlank() ->
                    "${item.displayName} · ${item.provider.displayLabel}"
                else ->
                    "${item.symbol} · ${item.provider.displayLabel} · ${item.status}"
            }
            Text(
                text = subText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Right Area: Live Price, 24h Change% (when available), Favorite & Add/Remove Action
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (liveTicker != null && liveTicker.lastPrice > 0.0) {
                val isUp = liveTicker.priceChangePercent >= 0.0
                val changeCol = if (isUp) BullishGreen else BearishRed
                val changeBg = if (isUp) BullishGreenSoft else BearishRedSoft
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = formatPriceWithPrefix(liveTicker.lastPrice, item.symbol, item.category),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(changeBg)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "${if (isUp) "+" else ""}${String.format(Locale.US, "%.2f", liveTicker.priceChangePercent)}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            color = changeCol,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onToggleFavorite() }
                    .testTag("search_fav_${item.symbol}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favorite Pair",
                    tint = if (isFavorite) NeutralAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            Surface(
                color = if (inWatchlist) BullishGreenSoft else CyanAccent.copy(alpha = 0.18f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .border(
                        1.dp,
                        if (inWatchlist) BullishGreen.copy(alpha = 0.6f) else CyanAccent.copy(alpha = 0.7f),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onToggleWatchlist() }
                    .testTag("toggle_watchlist_${item.symbol}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = if (inWatchlist) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = if (inWatchlist) "In Watchlist" else "Add to Watchlist",
                        tint = if (inWatchlist) BullishGreen else CyanAccent,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (inWatchlist) "Added" else "Add",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (inWatchlist) BullishGreen else CyanAccent,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * Primary Economic Calendar Screen (Tab 4: Kalender).
 * Reuses EconomicCalendarService (https://www.financecalendar.com/wp-json/fc/v1) and EventRiskAssessment.
 */
@Composable
fun EconomicCalendarScreen(
    activeSymbol: String,
    calendarState: CalendarLoadState,
    eventRiskAssessment: EventRiskAssessment,
    onRefreshCalendar: () -> Unit,
    calendarTimezoneOption: CalendarTimezoneOption = CalendarTimezoneOption.DEVICE,
    watchedCurrencies: Set<String> = CurrencyPairBiasEngine.DEFAULT_WATCHED_CURRENCIES,
    modifier: Modifier = Modifier
) {
    var calendarImpactFilter by remember { mutableStateOf(CalendarImpactFilter.ALL) }
    var calendarTimeView by remember { mutableStateOf(CalendarTimeView.UPCOMING) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("calendar_main_screen")
    ) {
        EconomicCalendarSubTab(
            activeSymbol = activeSymbol,
            calendarState = calendarState,
            eventRiskAssessment = eventRiskAssessment,
            impactFilter = calendarImpactFilter,
            timeView = calendarTimeView,
            onSelectImpactFilter = { calendarImpactFilter = it },
            onSelectTimeView = { calendarTimeView = it },
            onRefreshCalendar = onRefreshCalendar,
            calendarTimezoneOption = calendarTimezoneOption,
            watchedCurrencies = watchedCurrencies
        )
    }
}

/**
 * Requirement #4 & #5: Economic Calendar Sub-Tab inside Markets Screen.
 * - Displays economic event title, affected currency/country, event time (local / configured timezone), impact,
 *   forecast, previous, actual, pair biases, and source attribution when available.
 * - Supports High/Medium/Low impact filters and Today/Tomorrow/Upcoming views.
 * - Handles loading, empty, offline, rate-limit, and API-error states without inventing events.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EconomicCalendarSubTab(
    activeSymbol: String,
    calendarState: CalendarLoadState,
    eventRiskAssessment: EventRiskAssessment,
    impactFilter: CalendarImpactFilter,
    timeView: CalendarTimeView,
    onSelectImpactFilter: (CalendarImpactFilter) -> Unit,
    onSelectTimeView: (CalendarTimeView) -> Unit,
    onRefreshCalendar: () -> Unit,
    calendarTimezoneOption: CalendarTimezoneOption = CalendarTimezoneOption.DEVICE,
    watchedCurrencies: Set<String> = CurrencyPairBiasEngine.DEFAULT_WATCHED_CURRENCIES
) {
    val selectedTimeZone = remember(calendarTimezoneOption) {
        calendarTimezoneOption.getTimeZone()
    }
    val allEvents = calendarState.availableEvents
    val filteredEvents = remember(allEvents, impactFilter, timeView, selectedTimeZone) {
        EconomicCalendarService.filterEvents(
            events = allEvents,
            impactFilter = impactFilter,
            timeView = timeView,
            localTimeZone = selectedTimeZone
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("economic_calendar_subtab"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Event Risk Status Header for Active Pair (Requirement #5)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(10.dp)
                .testTag("calendar_event_risk_banner"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "EVENT RISK ENGINE · ${formatSymbolPair(activeSymbol)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "BOT DIRECTIVE: ${eventRiskAssessment.botActionDirective}",
                        style = MaterialTheme.typography.titleSmall,
                        color = when {
                            eventRiskAssessment.shouldBlockNewBotEntries -> BearishRed
                            !eventRiskAssessment.isEventDataVerified -> NeutralAmber
                            eventRiskAssessment.isEventWindowActive -> NeutralAmber
                            else -> BullishGreen
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = onRefreshCalendar,
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("refresh_calendar_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Economic Calendar",
                        tint = CyanAccent
                    )
                }
            }

            Text(
                text = eventRiskAssessment.summaryMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Source: FinanceCalendar",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = CyanAccent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.border(1.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                ) {
                    Text(
                        text = calendarTimezoneOption.headerBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // 2. Time View Selector (Today / Tomorrow / Upcoming) & Impact Filters (All / High / Medium / Low)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CalendarTimeView.entries.forEach { tv ->
                val isSel = timeView == tv
                Surface(
                    color = if (isSel) CyanAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (isSel) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectTimeView(tv) }
                        .testTag("calendar_time_${tv.name.lowercase()}")
                ) {
                    Text(
                        text = tv.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSel) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            CalendarImpactFilter.entries.forEach { imp ->
                val isSel = impactFilter == imp
                Surface(
                    color = if (isSel) CyanAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (isSel) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectImpactFilter(imp) }
                        .testTag("calendar_impact_${imp.name.lowercase()}")
                ) {
                    Text(
                        text = imp.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSel) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 3. State Banner for Loading / Offline / Rate-Limit / API Error
        when (calendarState) {
            is CalendarLoadState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(14.dp)
                        .testTag("calendar_loading_state"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = CyanAccent
                    )
                    Text(
                        text = "Loading economic calendar from FinanceCalendar...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            is CalendarLoadState.Offline -> {
                CalendarNoticeBanner(
                    title = "OFFLINE · CALENDAR FEED UNVERIFIED",
                    message = calendarState.message,
                    color = NeutralAmber,
                    onRetry = onRefreshCalendar,
                    tag = "calendar_offline_state"
                )
            }

            is CalendarLoadState.RateLimited -> {
                CalendarNoticeBanner(
                    title = "RATE LIMITED (HTTP 429)",
                    message = calendarState.message,
                    color = NeutralAmber,
                    onRetry = onRefreshCalendar,
                    tag = "calendar_ratelimit_state"
                )
            }

            is CalendarLoadState.ApiError -> {
                CalendarNoticeBanner(
                    title = "CALENDAR API UNAVAILABLE · UNCERTAINTY ACTIVE",
                    message = calendarState.message,
                    color = BearishRed,
                    onRetry = onRefreshCalendar,
                    tag = "calendar_error_state"
                )
            }

            is CalendarLoadState.Empty -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                        .testTag("calendar_empty_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = calendarState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            is CalendarLoadState.Success -> {
                if (calendarState.fromCache) {
                    Text(
                        text = "Showing cached calendar snapshot · Tap refresh for latest updates",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 4. Event List (if available from live response or cached snapshot)
        if (filteredEvents.isEmpty() && calendarState is CalendarLoadState.Success) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .padding(16.dp)
                    .testTag("calendar_filtered_empty_state"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No economic events match ${timeView.label} · ${impactFilter.label} Impact.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (filteredEvents.isNotEmpty()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("calendar_events_list")
            ) {
                items(filteredEvents, key = { it.id }) { ev ->
                    EconomicCalendarEventCard(
                        event = ev,
                        activeSymbol = activeSymbol,
                        calendarTimezone = calendarTimezoneOption,
                        watchedCurrencies = watchedCurrencies
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarNoticeBanner(
    title: String,
    message: String,
    color: Color,
    onRetry: () -> Unit,
    tag: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanAccent,
                    contentColor = Color(0xFF001F26)
                )
            ) {
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EconomicCalendarEventCard(
    event: EconomicCalendarEvent,
    activeSymbol: String,
    calendarTimezone: CalendarTimezoneOption = CalendarTimezoneOption.DEVICE,
    watchedCurrencies: Set<String> = CurrencyPairBiasEngine.DEFAULT_WATCHED_CURRENCIES
) {
    val impactLevel = EventImpactLevel.fromCode(event.impact)
    val (impColor, impBg) = when (impactLevel) {
        EventImpactLevel.HIGH -> BearishRed to BearishRedSoft
        EventImpactLevel.MEDIUM -> NeutralAmber to NeutralAmberSoft
        EventImpactLevel.LOW -> CyanAccent to CyanAccent.copy(alpha = 0.14f)
    }
    val isRelevantToActivePair = EventRiskManager.isEventRelevantToPair(
        currency = event.currency,
        country = event.country,
        impact = impactLevel,
        symbol = activeSymbol
    )

    val biasResult = remember(event, watchedCurrencies) {
        CurrencyPairBiasEngine.computeEventBias(event, watchedCurrencies)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(10.dp)
            .testTag("calendar_event_${event.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "${event.currency} · ${event.country}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = event.formatLocalTime(calendarTimezone.getTimeZone()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isRelevantToActivePair) {
                        Text(
                            text = "Affects ${formatSymbolPair(activeSymbol)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralAmber,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Surface(
                color = impBg,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.border(1.dp, impColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            ) {
                Text(
                    text = impactLevel.code,
                    style = MaterialTheme.typography.labelSmall,
                    color = impColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        // Bias pair badges for High Impact events (Masalah 4)
        if (biasResult != null && biasResult.pairBiases.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (biasResult.isPendingOrWaiting) NeutralAmber.copy(alpha = 0.35f) else CyanAccent.copy(alpha = 0.35f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(8.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            color = if (biasResult.isPendingOrWaiting) NeutralAmberSoft else CyanAccent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.border(
                                1.dp,
                                if (biasResult.isPendingOrWaiting) NeutralAmber.copy(alpha = 0.5f) else CyanAccent.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp)
                            )
                        ) {
                            Text(
                                text = "⚡ Bias Pair",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (biasResult.isPendingOrWaiting) NeutralAmber else CyanAccent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        biasResult.pairBiases.forEach { pairBias ->
                            val (textColor, bgColor, borderColor) = when (pairBias.direction) {
                                BiasDirection.BULLISH -> Triple(
                                    BullishGreen,
                                    BullishGreenSoft,
                                    BullishGreen.copy(alpha = 0.6f)
                                )
                                BiasDirection.BEARISH -> Triple(
                                    BearishRed,
                                    BearishRedSoft,
                                    BearishRed.copy(alpha = 0.6f)
                                )
                                BiasDirection.NEUTRAL -> Triple(
                                    MaterialTheme.colorScheme.onSurface,
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                )
                                BiasDirection.WAITING_DATA -> Triple(
                                    NeutralAmber,
                                    NeutralAmberSoft,
                                    NeutralAmber.copy(alpha = 0.5f)
                                )
                            }

                            Surface(
                                color = bgColor,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.border(1.dp, borderColor, RoundedCornerShape(4.dp))
                            ) {
                                Text(
                                    text = pairBias.displayLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = biasResult.implicationMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CalendarMetricCol("Forecast", event.getDisplayForecast(), Modifier.weight(1f))
            CalendarMetricCol("Previous", event.getDisplayPrevious(), Modifier.weight(1f))
            val actualVal = event.getDisplayActual()
            val actualColor = when (actualVal) {
                "Pending" -> NeutralAmber
                "--" -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> CyanAccent
            }
            CalendarMetricCol(
                label = "Actual",
                value = actualVal,
                modifier = Modifier.weight(1f),
                valueColor = actualColor
            )
            val cleanSource = if (event.source.startsWith("http") || event.source.contains("/")) "FinanceCalendar" else event.source.ifBlank { "FinanceCalendar" }
            CalendarMetricCol(
                label = "Source",
                value = cleanSource,
                modifier = Modifier.weight(1f),
                alignEnd = true
            )
        }
    }
}

@Composable
private fun CalendarMetricCol(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    alignEnd: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WatchlistRowCard(
    symbol: String,
    price: Double,
    changePercent: Double,
    quoteVolume: Double,
    category: InstrumentCategory = inferInstrumentCategory(symbol),
    provider: MarketProvider = inferMarketProvider(symbol, category),
    displayName: String = "",
    bidPrice: Double = 0.0,
    askPrice: Double = 0.0,
    marketStatus: MarketStatusEvaluation? = null,
    isSelected: Boolean,
    isFavorite: Boolean = false,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    showAddButton: Boolean = false,
    isSwipeOpen: Boolean = false,
    onSwipeOpenChange: (Boolean) -> Unit = {},
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit = {},
    onAdd: () -> Unit = {},
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val resolvedMarketStatus = marketStatus ?: remember(symbol, category, provider, price) {
        val now = System.currentTimeMillis()
        MarketStatusService.evaluate(
            symbol = symbol,
            category = category,
            provider = provider,
            fallbackQuoteTimestampMs = if (price > 0.0) now else null,
            nowMs = now
        )
    }
    val isPositive = changePercent >= 0.0
    val changeColor = if (isPositive) BullishGreen else BearishRed
    val changeBg = if (isPositive) BullishGreenSoft else BearishRedSoft
    val sign = if (isPositive) "+" else ""

    val density = LocalDensity.current
    val maxSwipeRevealPx = with(density) { 96.dp.toPx() }
    var dragDeltaPx by remember(symbol) { mutableFloatStateOf(0f) }
    var isDragging by remember(symbol) { mutableStateOf(false) }

    LaunchedEffect(isSwipeOpen) {
        if (!isSwipeOpen && !isDragging) {
            dragDeltaPx = 0f
        }
    }

    val baseOffsetPx = if (isSwipeOpen) -maxSwipeRevealPx else 0f
    val targetOffsetPx = if (isDragging) {
        (baseOffsetPx + dragDeltaPx).coerceIn(-maxSwipeRevealPx * 1.12f, 0f)
    } else {
        baseOffsetPx
    }
    val animatedOffsetPx by animateFloatAsState(
        targetValue = targetOffsetPx,
        label = "watchlist_swipe_offset"
    )
    val isBackgroundVisible = canRemove && (isSwipeOpen || animatedOffsetPx < -2f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isBackgroundVisible) BearishRed.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surface
            )
    ) {
        val isCompact = maxWidth < 370.dp

        // Background Delete Action Button revealed on the right when swiped left
        if (canRemove) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = BearishRed,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .width(80.dp)
                        .height(42.dp)
                        .clickable {
                            dragDeltaPx = 0f
                            isDragging = false
                            onSwipeOpenChange(false)
                            onRemove()
                        }
                        .testTag("swipe_delete_button_$symbol")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete $symbol",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Delete",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Foreground Watchlist Row Content
        Row(
            modifier = Modifier
                .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp)
                )
                .pointerInput(symbol, canRemove, isSwipeOpen) {
                    if (!canRemove) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragDeltaPx = 0f
                        },
                        onDragEnd = {
                            val startPx = if (isSwipeOpen) -maxSwipeRevealPx else 0f
                            val endPx = (startPx + dragDeltaPx).coerceIn(-maxSwipeRevealPx * 1.12f, 0f)
                            isDragging = false
                            dragDeltaPx = 0f
                            val shouldOpen = endPx <= -maxSwipeRevealPx * 0.38f
                            onSwipeOpenChange(shouldOpen)
                        },
                        onDragCancel = {
                            isDragging = false
                            dragDeltaPx = 0f
                            onSwipeOpenChange(isSwipeOpen)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val startPx = if (isSwipeOpen) -maxSwipeRevealPx else 0f
                            val nextTotal = (startPx + dragDeltaPx + dragAmount).coerceIn(-maxSwipeRevealPx * 1.12f, 0f)
                            dragDeltaPx = nextTotal - startPx
                        }
                    )
                }
                .clickable {
                    if (isSwipeOpen) {
                        onSwipeOpenChange(false)
                    } else {
                        onSelect()
                    }
                }
                .padding(horizontal = if (isCompact) 10.dp else 12.dp, vertical = 10.dp)
                .testTag("watchlist_item_$symbol"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT AREA: Symbol + horizontal ACTIVE badge + 24h Volume
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (isCompact) 6.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MarketStatusLedDot(
                        status = resolvedMarketStatus,
                        dotSize = 7.dp,
                        testTag = "watchlist_led_$symbol"
                    )
                    Text(
                        text = formatSymbolPair(symbol, category),
                        style = if (isCompact) {
                            MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp)
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("watchlist_symbol_text_$symbol")
                    )
                    if (isSelected) {
                        Surface(
                            color = CyanAccent.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .widthIn(min = 46.dp)
                                .testTag("watchlist_active_badge_$symbol")
                        ) {
                            Text(
                                text = "ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                val volumeLabel = when {
                    quoteVolume > 0.0 -> "Vol $${formatCompactVolume(quoteVolume)}"
                    bidPrice > 0.0 && askPrice > 0.0 ->
                        "${category.shortBadge} · Bid ${formatInstrumentPrice(bidPrice, symbol, category)} / Ask ${formatInstrumentPrice(askPrice, symbol, category)}"
                    displayName.isNotBlank() ->
                        "${category.shortBadge} · $displayName"
                    else ->
                        "${category.shortBadge} · ${provider.displayLabel}"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (isSelected) {
                        val statusFg = ledColorFor(resolvedMarketStatus.ledState)
                        val statusBg = ledSoftBgFor(resolvedMarketStatus.ledState)
                        Surface(
                            color = statusBg,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .border(1.dp, statusFg.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                                .testTag("watchlist_status_badge_$symbol")
                        ) {
                            Text(
                                text = resolvedMarketStatus.shortBadgeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusFg,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                    .testTag("watchlist_status_label_$symbol")
                            )
                        }
                    }
                    Text(
                        text = volumeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("watchlist_volume_text_$symbol")
                    )
                }
            }

            // RIGHT AREA: Last Price (Monospace), 24h % Change, and Compact Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 10.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = if (price > 0.0) formatPriceWithPrefix(price, symbol, category) else "--",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = if (isCompact) 12.sp else 13.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.testTag("watchlist_price_text_$symbol")
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(changeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$sign${String.format(Locale.US, "%.2f", changePercent)}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            color = changeColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("watchlist_change_text_$symbol")
                        )
                    }
                }

                // Compact Action Controls (never squeezes price or symbol)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isCompact) 2.dp else 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggleFavorite)
                            .testTag("fav_pair_$symbol"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Toggle Favorite",
                            tint = if (isFavorite) NeutralAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    if (showAddButton) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onAdd)
                                .testTag("add_pair_$symbol"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Pair",
                                tint = CyanAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 22.dp, height = 19.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(enabled = canMoveUp, onClick = onMoveUp)
                                    .testTag("move_up_$symbol"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Move Up",
                                    tint = if (canMoveUp) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(width = 22.dp, height = 19.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(enabled = canMoveDown, onClick = onMoveDown)
                                    .testTag("move_down_$symbol"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Move Down",
                                    tint = if (canMoveDown) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    if (canRemove) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onRemove)
                                .testTag("remove_pair_$symbol"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Remove Pair",
                                tint = BearishRed.copy(alpha = 0.85f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact Watchlist section embedded at the bottom of the Main Chart screen (Requirement #12).
 */
@Composable
fun CompactWatchlistSection(
    activeSymbol: String,
    watchlist: List<WatchlistEntity>,
    liveTickers: Map<String, Ticker24h>,
    onSelectSymbol: (String) -> Unit,
    onOpenMarketsTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("compact_watchlist_section")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "WATCHLIST",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
            Surface(
                color = CyanAccent.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .clickable(onClick = onOpenMarketsTab)
                    .testTag("manage_watchlist_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add or Search Pair",
                        tint = CyanAccent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Add / Search Pair",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        watchlist.forEach { item ->
            val live = liveTickers[item.symbol]
            val price = live?.lastPrice ?: item.lastPrice
            val change = live?.priceChangePercent ?: item.priceChangePercent
            val isPos = change >= 0.0
            val changeCol = if (isPos) BullishGreen else BearishRed
            val isActive = item.symbol.equals(activeSymbol, ignoreCase = true)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isActive) CyanAccent.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                    .clickable { onSelectSymbol(item.symbol) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("compact_watchlist_row_${item.symbol}"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatSymbolPair(item.symbol),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isActive) CyanAccent else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (price > 0.0) formatPriceWithPrefix(price, item.symbol) else "--",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "${if (isPos) "+" else ""}${String.format(Locale.US, "%.2f", change)}%",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = changeCol,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
