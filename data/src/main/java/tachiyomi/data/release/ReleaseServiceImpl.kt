package tachiyomi.data.release

import android.os.Build
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        val release = with(json) {
            networkService.client
                .newCall(GET("https://api.github.com/repos/${arguments.repository}/releases/latest"))
                .awaitSuccess()
                .parseAs<GithubRelease>()
        }

        val downloadLink = getDownloadLink(release = release, isFoss = arguments.isFoss) ?: return null

        return Release(
            version = release.version,
            info = release.info.substringBeforeLast("<!-->").replace(gitHubUsernameMentionRegex) { mention ->
                "[${mention.value}](https://github.com/${mention.value.substring(1)})"
            },
            releaseLink = release.releaseLink,
            downloadLink = downloadLink,
        )
    }

    private fun getDownloadLink(release: GithubRelease, isFoss: Boolean): String? {
        val map = release.assets.associate { asset ->
            BUILD_TYPES.find { "-$it" in asset.name } to asset.downloadLink
        }

        return if (!isFoss) {
            // Try the device's own ABI first, then fall back to the
            // architecture-generic (universal) entry. Covers all 5 shipped
            // ABIs (arm64-v8a, armeabi-v7a, x86_64, x86, universal) — the
            // older matcher only keyed 3 suffixes so v7a/x86 devices silently
            // lost their download link when the universal APK was absent.
            val abiLink = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                val key = ABI_TO_BUILD_TYPE[abi]
                key?.let { map[it] }
            }
            abiLink ?: map[UNIVERSAL]
        } else {
            map[FOSS]
        }
    }

    companion object {
        private const val FOSS = "foss"
        private const val UNIVERSAL = "universal"
        private val BUILD_TYPES = listOf(FOSS, "arm64-v8a", "armeabi-v7a", "x86_64", "x86", UNIVERSAL)
        private val ABI_TO_BUILD_TYPE = mapOf(
            "arm64-v8a" to "arm64-v8a",
            "riscv64" to "universal",
            "armeabi-v7a" to "armeabi-v7a",
            "armeabi" to "armeabi-v7a",
            "x86_64" to "x86_64",
            "x86" to "x86",
        )

        /**
         * Regular expression that matches a mention to a valid GitHub username, like it's
         * done in GitHub Flavored Markdown. It follows these constraints:
         *
         * - Alphanumeric with single hyphens (no consecutive hyphens)
         * - Cannot begin or end with a hyphen
         * - Max length of 39 characters
         *
         * Reference: https://stackoverflow.com/a/30281147
         */
        private val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)
    }
}
