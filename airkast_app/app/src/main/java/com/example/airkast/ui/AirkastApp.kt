package com.example.airkast.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.airkast.MainViewModel
import com.example.airkast.R
import com.example.airkast.ui.components.AreaSelectionDialog
import com.example.airkast.ui.components.DownloadChunkSizeDialog
import com.example.airkast.ui.components.PlayerOverlay
import com.example.airkast.ui.screens.DownloadsScreen
import com.example.airkast.ui.screens.FindProgramsScreen
import com.example.airkast.ui.theme.AirkastMotion
import com.example.airkast.ui.theme.MinTouchTarget
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import kotlinx.coroutines.launch

/** アプリ全体のシェル: トップバー + コンテンツ + ナビゲーションバー + 浮遊ミニプレイヤー。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel) {
    var tabIndex by rememberSaveable { mutableStateOf(TAB_FIND) }
    var showAreaDialog by remember { mutableStateOf(false) }
    var showChunkSizeDialog by remember { mutableStateOf(false) }
    var playerReservedHeight by remember { mutableStateOf(0.dp) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // メッセージはキューに積む(表示中でも次のメッセージの受信を止めない)
    LaunchedEffect(viewModel) {
        viewModel.userMessage.collect { message ->
            launch { snackbarHostState.showSnackbar(message) }
        }
    }
    // タブを切り替えたらトップバーの「スクロール済み」状態を戻す
    LaunchedEffect(tabIndex) { scrollBehavior.state.contentOffset = 0f }

    val reserved by animateDpAsState(
        targetValue = playerReservedHeight,
        animationSpec = AirkastMotion.spatial(),
        label = "playerReserved",
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AirkastTopBar(
                title = stringResource(if (tabIndex == TAB_FIND) R.string.nav_find else R.string.nav_downloads),
                scrollBehavior = scrollBehavior,
                onAreaClick = { showAreaDialog = true },
                onDownloadSettingsClick = { showChunkSizeDialog = true },
                onLicensesClick = {
                    context.startActivity(Intent(context, OssLicensesMenuActivity::class.java))
                },
            )
        },
        snackbarHost = {
            // ミニプレイヤーに隠れないよう、その分だけ持ち上げる
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(bottom = reserved))
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tabIndex == TAB_FIND,
                    onClick = { tabIndex = TAB_FIND },
                    icon = {
                        Icon(
                            imageVector = if (tabIndex == TAB_FIND) Icons.Filled.Search else Icons.Outlined.Search,
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(R.string.nav_find)) },
                )
                NavigationBarItem(
                    selected = tabIndex == TAB_DOWNLOADS,
                    onClick = { tabIndex = TAB_DOWNLOADS },
                    icon = {
                        Icon(
                            imageVector = if (tabIndex == TAB_DOWNLOADS) Icons.Filled.Download else Icons.Outlined.Download,
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(R.string.nav_downloads)) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedContent(
                targetState = tabIndex,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(AirkastMotion.spatial<IntOffset>()) { it / 8 * direction } +
                        fadeIn(AirkastMotion.effects())) togetherWith
                        (slideOutHorizontally(AirkastMotion.spatial<IntOffset>()) { -it / 8 * direction } +
                            fadeOut(AirkastMotion.effects()))
                },
                label = "tabContent",
            ) { tab ->
                when (tab) {
                    TAB_FIND -> FindProgramsScreen(viewModel, contentBottomPadding = reserved)
                    else -> DownloadsScreen(viewModel, contentBottomPadding = reserved)
                }
            }

            PlayerOverlay(
                viewModel = viewModel,
                onReservedHeightChange = { playerReservedHeight = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showAreaDialog) {
        AreaSelectionDialog(
            currentAreaId = viewModel.selectedAreaId.collectAsState().value,
            onAreaSelected = {
                viewModel.onAreaSelected(it)
                showAreaDialog = false
            },
            onDismiss = { showAreaDialog = false },
        )
    }

    if (showChunkSizeDialog) {
        DownloadChunkSizeDialog(
            currentChunkSize = viewModel.downloadChunkSize.collectAsState().value,
            onChunkSizeSelected = { viewModel.setDownloadChunkSize(it) },
            onDismiss = { showChunkSizeDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirkastTopBar(
    title: String,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onAreaClick: () -> Unit,
    onDownloadSettingsClick: () -> Unit,
    onLicensesClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        actions = {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(MinTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.menu_more),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_area)) },
                        onClick = {
                            menuExpanded = false
                            onAreaClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_download_settings)) },
                        onClick = {
                            menuExpanded = false
                            onDownloadSettingsClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_licenses)) },
                        onClick = {
                            menuExpanded = false
                            onLicensesClick()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}
