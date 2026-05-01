package org.tasks.compose.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrColors
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.tasks.billing.GitHubSponsorClient
import org.tasks.billing.LinkResult
import org.tasks.billing.StatusResult
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.back
import tasks.kmp.generated.resources.done
import tasks.kmp.generated.resources.error_create_link_failed
import tasks.kmp.generated.resources.error_github_verification_failed
import tasks.kmp.generated.resources.error_not_a_sponsor
import tasks.kmp.generated.resources.link_desktop_success
import tasks.kmp.generated.resources.qr_code
import tasks.kmp.generated.resources.retry
import tasks.kmp.generated.resources.sponsor_on_github
import tasks.kmp.generated.resources.unlock_pro
import tasks.kmp.generated.resources.unlock_pro_github_instructions
import tasks.kmp.generated.resources.unlock_pro_instructions
import tasks.kmp.generated.resources.unlock_pro_opening_browser
import tasks.kmp.generated.resources.unlock_pro_via_github
import tasks.kmp.generated.resources.unlock_pro_via_mobile
import tasks.kmp.generated.resources.unlock_pro_with_github

sealed interface DesktopProState {
    data object Loading : DesktopProState
    data class ShowQr(val code: String, val expiresAt: Long) : DesktopProState
    data object Success : DesktopProState
    data class Error(val message: String) : DesktopProState
}

sealed interface GitHubProState {
    data object Idle : GitHubProState
    data object OpeningBrowser : GitHubProState
    data object Success : GitHubProState
    data object NotSponsor : GitHubProState
    data object Failed : GitHubProState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopProScreen(
    onBack: () -> Unit,
    onCreateLink: suspend () -> LinkResult?,
    onPollStatus: suspend (code: String) -> StatusResult?,
    onLinkSuccess: suspend (jwt: String, refreshToken: String, sku: String?, formattedPrice: String?) -> Unit,
    onGitHubSignIn: suspend () -> GitHubSponsorClient.VerifyResult,
    onOpenSponsorPage: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var qrState by remember { mutableStateOf<DesktopProState>(DesktopProState.Loading) }
    var gitHubState by remember { mutableStateOf<GitHubProState>(GitHubProState.Idle) }
    var linkGeneration by remember { mutableIntStateOf(0) }
    val errorMessage = stringResource(Res.string.error_create_link_failed)
    val scope = rememberCoroutineScope()

    // Create or refresh link
    LaunchedEffect(linkGeneration) {
        qrState = DesktopProState.Loading
        val result = onCreateLink()
        if (result != null) {
            qrState = DesktopProState.ShowQr(result.code, result.expiresAt)
        } else {
            qrState = DesktopProState.Error(errorMessage)
        }
    }

    // Poll for status, auto-renew on expiry
    val pollCode = (qrState as? DesktopProState.ShowQr)?.code
    LaunchedEffect(pollCode) {
        val showQr = qrState as? DesktopProState.ShowQr ?: return@LaunchedEffect
        var consecutiveErrors = 0
        while (true) {
            val now = currentTimeMillis() / 1000
            if (showQr.expiresAt in 1..now) {
                linkGeneration++
                return@LaunchedEffect
            }
            delay(if (consecutiveErrors > 0) 15_000L else 5_000L)
            val status = onPollStatus(showQr.code)
            if (status == null) {
                consecutiveErrors++
                continue
            }
            consecutiveErrors = 0
            if (status.status == "confirmed" && status.jwt != null && status.refreshToken != null) {
                onLinkSuccess(status.jwt, status.refreshToken, status.sku, status.formattedPrice)
                qrState = DesktopProState.Success
                break
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
                title = {
                    Text(text = stringResource(Res.string.unlock_pro))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(Res.string.unlock_pro_via_mobile)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(Res.string.unlock_pro_via_github)) },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 400.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (selectedTab) {
                        0 -> QrTab(
                            state = qrState,
                            onBack = onBack,
                            onRetry = { linkGeneration++ },
                        )
                        1 -> GitHubTab(
                            state = gitHubState,
                            onBack = onBack,
                            onSignIn = {
                                gitHubState = GitHubProState.OpeningBrowser
                                scope.launch {
                                    val result = onGitHubSignIn()
                                    gitHubState = when (result) {
                                        GitHubSponsorClient.VerifyResult.Success ->
                                            GitHubProState.Success
                                        GitHubSponsorClient.VerifyResult.NotSponsor ->
                                            GitHubProState.NotSponsor
                                        GitHubSponsorClient.VerifyResult.Failed ->
                                            GitHubProState.Failed
                                    }
                                }
                            },
                            onOpenSponsorPage = onOpenSponsorPage,
                            onRetry = { gitHubState = GitHubProState.Idle },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrTab(
    state: DesktopProState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    when (val s = state) {
        is DesktopProState.Loading -> {
            CircularProgressIndicator()
        }
        is DesktopProState.ShowQr -> {
            Text(
                text = stringResource(Res.string.unlock_pro_instructions),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsContentPadding),
            )
            Spacer(modifier = Modifier.height(32.dp))
            Image(
                painter = rememberQrCodePainter(
                    data = s.code,
                    colors = QrColors(
                        dark = QrBrush.solid(MaterialTheme.colorScheme.onSurface),
                    ),
                ),
                contentDescription = stringResource(Res.string.qr_code),
                modifier = Modifier.size(256.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
        is DesktopProState.Success -> {
            SuccessContent(onBack = onBack)
        }
        is DesktopProState.Error -> {
            Text(
                text = s.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text(stringResource(Res.string.retry))
            }
        }
    }
}

@Composable
private fun GitHubTab(
    state: GitHubProState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onOpenSponsorPage: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is GitHubProState.Idle -> {
            Text(
                text = stringResource(Res.string.unlock_pro_github_instructions),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsContentPadding),
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onSignIn) {
                Text(stringResource(Res.string.unlock_pro_with_github))
            }
        }
        is GitHubProState.OpeningBrowser -> {
            Text(
                text = stringResource(Res.string.unlock_pro_opening_browser),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
        is GitHubProState.Success -> {
            SuccessContent(onBack = onBack)
        }
        is GitHubProState.NotSponsor -> {
            Text(
                text = stringResource(Res.string.error_not_a_sponsor),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsContentPadding),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onOpenSponsorPage) {
                Text(stringResource(Res.string.sponsor_on_github))
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) {
                Text(stringResource(Res.string.retry))
            }
        }
        is GitHubProState.Failed -> {
            Text(
                text = stringResource(Res.string.error_github_verification_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text(stringResource(Res.string.retry))
            }
        }
    }
}

@Composable
private fun SuccessContent(onBack: () -> Unit) {
    Text(
        text = stringResource(Res.string.link_desktop_success),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(onClick = onBack) {
        Text(stringResource(Res.string.done))
    }
}
