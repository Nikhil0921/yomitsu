package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.WhatsNewSheet
import eu.kanade.presentation.more.settings.widget.PreferenceGroupCard
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.updaterEnabled
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LinkIcon
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Discord
import tachiyomi.presentation.core.icons.Facebook
import tachiyomi.presentation.core.icons.Github
import tachiyomi.presentation.core.icons.Reddit
import tachiyomi.presentation.core.icons.X
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object AboutScreen : Screen() {

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val handleBack = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow
        var isCheckingUpdates by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_category_about),
                    navigateUp = if (handleBack != null) handleBack::invoke else null,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(
                contentPadding = contentPadding,
            ) {
                item {
                    LogoHeader(
                        iconPadding = PaddingValues(vertical = 56.dp),
                    )
                }

                // App/version rows and support rows as two unheaded grouped surfaces
                item {
                    PreferenceGroupCard(title = null) {
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.version),
                            subtitle = getVersionName(withBuildDate = true),
                            onPreferenceClick = {
                                val deviceInfo = CrashLogUtil(context).getDebugInfo()
                                context.copyToClipboard("Debug information", deviceInfo)
                            },
                        )

                        if (updaterEnabled) {
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.check_for_updates),
                                widget = {
                                    AnimatedVisibility(visible = isCheckingUpdates) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 3.dp,
                                        )
                                    }
                                },
                                onPreferenceClick = {
                                    if (!isCheckingUpdates) {
                                        scope.launch {
                                            isCheckingUpdates = true

                                            checkVersion(
                                                context = context,
                                                onAvailableUpdate = { result ->
                                                    val updateScreen = NewUpdateScreen(
                                                        versionName = result.release.version,
                                                        changelogInfo = result.release.info,
                                                        releaseLink = result.release.releaseLink,
                                                        downloadLink = result.release.downloadLink,
                                                    )
                                                    navigator.push(updateScreen)
                                                },
                                                onFinish = {
                                                    isCheckingUpdates = false
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }

                        if (!BuildConfig.DEBUG) {
                            var whatsNewRelease by remember { mutableStateOf<Release?>(null) }
                            var whatsNewError by remember { mutableStateOf(false) }
                            var showWhatsNew by remember { mutableStateOf(false) }
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.whats_new),
                                onPreferenceClick = {
                                    scope.launch {
                                        if (whatsNewRelease == null && !whatsNewError) {
                                            runCatching {
                                                whatsNewRelease = AppUpdateChecker().fetchLatestRelease()
                                            }.onFailure {
                                                whatsNewError = true
                                            }
                                        }
                                        showWhatsNew = true
                                    }
                                },
                            )
                            if (showWhatsNew) {
                                WhatsNewSheet(
                                    versionName = whatsNewRelease?.version ?: "Latest",
                                    markdown = whatsNewRelease?.info.orEmpty(),
                                    releaseLink = whatsNewRelease?.releaseLink ?: RELEASE_URL,
                                    onOpenInBrowser = {
                                        uriHandler.openUri(whatsNewRelease?.releaseLink ?: RELEASE_URL)
                                    },
                                    onDismiss = { showWhatsNew = false },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    PreferenceGroupCard(title = null) {
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.licenses),
                            onPreferenceClick = { navigator.push(OpenSourceLicensesScreen()) },
                        )

                        // Unconfigured by the maintainer today — intercept and
                        // toast instead of opening a possibly-dead external URL.
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.privacy_policy),
                            onPreferenceClick = {
                                context.toast(MR.strings.link_not_configured)
                            },
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .padding(horizontal = MaterialTheme.padding.extraSmall),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        // Website / Discord: unconfigured by the maintainer —
                        // intercepted with a non-intrusive toast instead of a
                        // possibly-dead external URL.
                        UnconfiguredLinkIcon(
                            label = stringResource(MR.strings.website),
                            icon = Icons.Outlined.Public,
                            context = context,
                        )
                        UnconfiguredLinkIcon(
                            label = "Discord",
                            icon = CustomIcons.Discord,
                            context = context,
                        )
                        /*
                        Not currently used socials
                        LinkIcon(
                            label = "X",
                            icon = CustomIcons.X,
                            url = "https://x.com/mihonapp",
                        )
                        LinkIcon(
                            label = "Facebook",
                            icon = CustomIcons.Facebook,
                            url = "https://facebook.com/mihonapp",
                        )
                        LinkIcon(
                            label = "Reddit",
                            icon = CustomIcons.Reddit,
                            url = "https://www.reddit.com/r/mihonapp",
                        )
                         */
                        LinkIcon(
                            label = "GitHub",
                            icon = CustomIcons.Github,
                            url = "https://github.com/Nikhil0921/yomitsu",
                        )
                    }
                }
            }
        }
    }

    /**
     * Looks like a [LinkIcon] but intercepts the tap with a "not configured"
     * toast for external destinations the maintainer has not set up.
     */
    @Composable
    private fun UnconfiguredLinkIcon(
        label: String,
        icon: ImageVector,
        context: Context,
    ) {
        IconButton(
            modifier = Modifier.padding(4.dp),
            onClick = { context.toast(MR.strings.link_not_configured) },
        ) {
            Icon(
                imageVector = icon,
                tint = MaterialTheme.colorScheme.primary,
                contentDescription = label,
            )
        }
    }

    /**
     * Checks version and shows a user prompt if an update is available.
     */
    private suspend fun checkVersion(
        context: Context,
        onAvailableUpdate: (GetApplicationRelease.Result.NewUpdate) -> Unit,
        onFinish: () -> Unit,
    ) {
        val updateChecker = AppUpdateChecker()
        withUIContext {
            try {
                when (val result = withIOContext { updateChecker.checkForUpdate(context, forceCheck = true) }) {
                    is GetApplicationRelease.Result.NewUpdate -> {
                        onAvailableUpdate(result)
                    }
                    is GetApplicationRelease.Result.NoNewUpdate -> {
                        context.toast(MR.strings.update_check_no_new_updates)
                    }
                    is GetApplicationRelease.Result.OsTooOld -> {
                        context.toast(MR.strings.update_check_eol)
                    }
                }
            } catch (e: Exception) {
                context.toast(e.message)
                logcat(LogPriority.ERROR, e)
            } finally {
                onFinish()
            }
        }
    }

    fun getVersionName(withBuildDate: Boolean): String {
        return when {
            BuildConfig.DEBUG -> {
                "Debug ${BuildConfig.COMMIT_SHA}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
            isPreviewBuildType -> {
                "Beta r${BuildConfig.COMMIT_COUNT}".let {
                    if (withBuildDate) {
                        "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                    } else {
                        "$it (${BuildConfig.COMMIT_SHA})"
                    }
                }
            }
            else -> {
                "Stable ${BuildConfig.VERSION_NAME}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
        }
    }

    internal fun getFormattedBuildTime(): String {
        return try {
            LocalDateTime.ofInstant(
                Instant.parse(BuildConfig.BUILD_TIME),
                ZoneId.systemDefault(),
            )
                .toDateTimestampString(
                    UiPreferences.dateFormat(
                        Injekt.get<UiPreferences>().dateFormat.get(),
                    ),
                )
        } catch (e: Exception) {
            BuildConfig.BUILD_TIME
        }
    }
}
