package dev.amenhancer.module.hook

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsFileReader
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Apple Music 6.5.0 adapter for user-managed, offline ID -> TTML mappings. */
internal class AppleMusicCustomLyricsTarget(
    private val application: Application,
    private val config: TargetConfigClient,
    private val symbols: TargetSymbolResolver,
    private val currentSong: CurrentSongIdentityCache,
    private val autoLyricsRuntime: AutoLyricsRuntime? = null,
) : CustomLyricsTarget {
    override fun install(): TargetCapabilityInstall {
        val installMethodResolution = symbols.resolve(AppleMusicSymbols.LyricsInstallMethod)
        val installMethod = installMethodResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(installMethodResolution.summary)
        if (!runCatching {
                installMethod.isAccessible = true
                true
            }.getOrDefault(false)
        ) {
            return TargetCapabilityInstall.Degraded(
                "PlayerLyricsViewFragment.I2 could not be made accessible; " +
                    installMethodResolution.summary,
            )
        }
        val ptrResolution = symbols.resolve(AppleMusicSymbols.SongInfoPtr)
        val ptrClass = ptrResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(ptrResolution.summary)
        val nativeResolution = symbols.resolve(AppleMusicSymbols.SongInfoNative)
        val nativeClass = nativeResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(nativeResolution.summary)
        val parserResolution = symbols.resolve(AppleMusicSymbols.TtmlParserNative)
        val parserClass = parserResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(parserResolution.summary)
        val parseMethodResolution = symbols.resolve(AppleMusicSymbols.TtmlSongInfoFromTtml)
        val parseMethod = parseMethodResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(parseMethodResolution.summary)
        val seam = CurrentItemIdentitySeam(symbols)
        seam.resolve(installMethod)?.let { diagnostic ->
            return TargetCapabilityInstall.Degraded(diagnostic)
        }
        val parser = TtmlNativeParser.create(
            parserClass = parserClass,
            parseMethod = parseMethod,
            ptrClass = ptrClass,
            nativeClass = nativeClass,
        ) ?: return TargetCapabilityInstall.Degraded(
            "TTML native parser surface was unavailable; " +
                listOf(
                    parserResolution.summary,
                    parseMethodResolution.summary,
                    ptrResolution.summary,
                    nativeResolution.summary,
                ).joinToString("; "),
        )
        val timingObservations = TtmlTimingObservationRegistry()
        val fileReader = CustomLyricsFileReader { fileId ->
            config.openFile(fileId)?.let { input ->
                runCatching {
                    input.use(CustomLyricsFilePolicy::readBounded)
                }.getOrNull()
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        var activeFragment: WeakReference<Any>? = null
        lateinit var readyReapply: CustomLyricsReadyReapply
        val configuredManualIds = runCatching {
            config.customLyricsManifest().entries
                .filter { it.enabled && it.source != CustomLyricsSources.AUTO_CACHE }
                .mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
        }.getOrDefault(emptySet())
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                config.customLyricsManifest().entries
                    .filter { it.source != CustomLyricsSources.AUTO_CACHE }
                    .associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = fileReader::read,
            parseTtml = parser::parse,
            isAlive = parser::isAlive,
            verifyPtr = parser::isValid,
            readAdamId = parser::adamIdOf,
            bindAdamId = parser::bindAdamId,
            onReplacementPublished = { appleMusicId ->
                mainHandler.post { readyReapply.onReplacementPublished(appleMusicId) }
            },
            executor = ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(1),
                { runnable -> Thread(runnable, "ampp-custom-lyrics").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            ),
            logger = ModernXposedRuntime::log,
        )
        val autoSession = autoLyricsRuntime?.let { runtime ->
            AutoLyricsReplacementSession(
                fetchCandidate = { appleMusicId ->
                    val current = currentSong.current()
                        ?.takeIf { it.details.appleMusicId == appleMusicId }
                    val metadata = MediaMetadataCache.getMetadataById(appleMusicId.toString())
                    val track = current?.details?.let { details ->
                        val item = current.item
                        DesktopLyricsTrack(
                            appleMusicId = appleMusicId,
                            explicitSource = CurrentLyricsSourceStatus.selectedSource(application, appleMusicId) != null,
                            title = details.title?.takeIf(String::isNotBlank)
                                ?: metadata?.title.orEmpty(),
                            artist = details.artist?.takeIf(String::isNotBlank)
                                ?: metadata?.artist.orEmpty(),
                            album = itemAlbum(item)
                                ?: metadata?.originalAlbum.orEmpty(),
                            durationMs = itemNumber(item, "getDuration")
                                ?: metadata?.duration?.coerceAtLeast(0L) ?: 0L,
                        )
                    }
                    ModernXposedRuntime.log(
                        "Desktop Lyrics lookup id=$appleMusicId " +
                            "title=${track?.title.orEmpty().take(48)} " +
                            "artist=${track?.artist.orEmpty().take(48)} " +
                            "album=${track?.album.orEmpty().take(48)} " +
                            "durationMs=${track?.durationMs ?: 0L}",
                    )
                    val resolved = runtime.resolver.fetch(appleMusicId, track)
                    ModernXposedRuntime.log(
                        "Desktop Lyrics lookup result id=$appleMusicId source=${resolved?.source ?: "none"}",
                    )
                    resolved?.let { candidate ->
                        val details = currentSong.current()
                            ?.takeIf { it.details.appleMusicId == appleMusicId }
                            ?.details
                        val displayName = listOfNotNull(
                            details?.title?.takeIf(String::isNotBlank),
                            details?.artist?.takeIf(String::isNotBlank),
                        ).joinToString(" - ").ifBlank { null }
                        candidate.copy(displayName = displayName)
                    }
                },
                cache = runtime.cache,
                onRefreshFinished = { id, success ->
                    mainHandler.post {
                        if (currentSong.current()?.details?.appleMusicId == id) {
                            android.widget.Toast.makeText(application,
                                if (success) "歌词已更新" else "未找到新的可靠歌词，已保留当前歌词",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onCandidatePrepared = { id, candidate ->
                    if (candidate.source != CustomLyricsSources.AUTO_CACHE) {
                        CurrentLyricsSourceStatus.rememberCandidate(
                            application, id, candidate.source, "<translations>" in candidate.ttml,
                        )
                    }
                },
                parseTtml = parser::parse,
                isAlive = parser::isAlive,
                verifyPtr = parser::isValid,
                readAdamId = parser::adamIdOf,
                bindAdamId = parser::bindAdamId,
                onReplacementPublished = { appleMusicId ->
                    mainHandler.post { readyReapply.onReplacementPublished(appleMusicId) }
                },
                // Keep automatic results outside manual mappings so a later
                // Desktop Lyrics lookup can supersede an older fallback.
                publisher = null,
                isAllowed = { appleMusicId ->
                    appleMusicId !in runtime.suppressedIds &&
                        appleMusicId !in configuredManualIds &&
                        !session.isMapped(appleMusicId) &&
                        session.readyReplacementFor(appleMusicId) == null
                },
                executor = runtime.executor,
                logger = ModernXposedRuntime::log,
            )
        }
        val readyReplacementFor: (Long) -> Any? = { appleMusicId ->
            session.readyReplacementFor(appleMusicId)
                ?: autoSession?.readyReplacementFor(appleMusicId)
        }
        val isTracking: (Long) -> Boolean = { appleMusicId ->
            session.isTracking(appleMusicId) || autoSession?.isTracking(appleMusicId) == true
        }
        val fragmentUsable = fragmentIsAddedPredicate(installMethod.declaringClass)
        readyReapply = CustomLyricsReadyReapply(
            installMethod = installMethod,
            seam = seam,
            readyReplacementFor = readyReplacementFor,
            isFragmentUsable = fragmentUsable,
            currentSong = currentSong,
            logger = ModernXposedRuntime::log,
        )
        val parserHooked = runCatching {
            parseMethod.isAccessible = true
            ModernXposedRuntime.hookMethod(parseMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val ttml = param.args.getOrNull(0) as? String ?: return@runCatching
                        val pointer = param.result
                        val metadata = TtmlTimingPolicy.metadataOf(ttml)
                        val appleMusicId = pointer?.let(parser::adamIdOf)
                        timingObservations.record(pointer, metadata, appleMusicId)
                        if (
                            appleMusicId != null &&
                            currentSong.current()?.details?.appleMusicId == appleMusicId &&
                            session.readyReplacementFor(appleMusicId) == null
                        ) {
                            autoSession?.ensureRequested(appleMusicId)
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics TTML timing observation failed: $error")
                    }
                }
            })
        }.isSuccess
        val itemUpdateContext = LyricsItemUpdateContext()
        val hooked = runCatching {
            ModernXposedRuntime.hookMethod(installMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    itemUpdateContext.markAppleInvokedI2()
                    runCatching {
                        if (!acceptsLyricsInstallArguments(param.args, ptrClass)) return@runCatching
                        val original = param.args[0]
                        val fragmentAdamId = seam.currentItemAdamIdOf(param.thisObject)
                        val publishedCurrent = currentSong.current()
                        val publishedAdamId = publishedCurrent?.details?.appleMusicId
                        val adamId = selectLyricsInjectionAdamId(
                            original = original,
                            fragmentAdamId = fragmentAdamId,
                            publishedAdamId = publishedAdamId,
                        )
                        ModernXposedRuntime.log(
                            "Desktop Lyrics I2 original=${original != null} fragmentId=${fragmentAdamId ?: 0L} " +
                                "currentId=${publishedAdamId ?: 0L} selectedId=${adamId ?: 0L}",
                        )
                        adamId ?: return@runCatching
                        param.thisObject?.let { activeFragment = WeakReference(it) }
                        val manualReplacement = session.replacementFor(adamId)
                        val timingMetadata = timingObservations.metadataOf(original)
                        val autoEligible = autoSession != null
                        val autoReplacement = if (manualReplacement == null) {
                            autoSession?.replacementFor(adamId)
                        } else null
                        // User-managed mappings win. Desktop Lyrics is first
                        // among automatic sources, even when Apple has Word TTML.
                        val replacement = manualReplacement ?: autoReplacement
                        ModernXposedRuntime.log(
                            "Desktop Lyrics I2 choice id=$adamId manual=${manualReplacement != null} " +
                                "auto=${autoReplacement != null} tracking=${autoSession?.isTracking(adamId) == true}",
                        )
                        val tracking = session.isTracking(adamId) ||
                            (autoEligible && autoSession?.isTracking(adamId) == true)
                        val needsRebind = original == null &&
                            publishedCurrent != null &&
                            publishedAdamId != null &&
                            publishedAdamId != fragmentAdamId
                        val canRebind = currentSong.canRebind(fragmentAdamId, publishedAdamId)
                        if (
                            needsRebind && tracking && canRebind &&
                            (fragmentAdamId == null || session.isMapped(adamId))
                        ) {
                            val rebound = param.thisObject?.let { fragment ->
                                seam.bindCurrentItemOf(fragment, publishedCurrent.item)
                            } == true
                            if (!rebound) return@runCatching
                        }
                        if (replacement == null) {
                            if (shouldRecordReadyLateMiss(original, replacement) && tracking) {
                                param.thisObject?.let { readyReapply.recordMiss(it, adamId) }
                            }
                        } else {
                            if (manualReplacement == null && autoReplacement != null) {
                                autoSession?.markTakeoverApplied(adamId)
                            }
                            param.thisObject?.let { readyReapply.dismiss(it) }
                            CurrentLyricsSourceStatus.recordApplied(
                                application, adamId, manualReplacement != null,
                            )
                            if (replacement !== original) {
                                param.args[0] = replacement
                                ModernXposedRuntime.log("Desktop Lyrics I2 installed id=$adamId")
                            }
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics I2 replacement hook failed: $error")
                    }
                }
            })
        }.isSuccess
        if (!hooked) {
            return TargetCapabilityInstall.Degraded(
                "PlayerLyricsViewFragment.I2 could not be hooked; ${installMethodResolution.summary}",
            )
        }
        val itemUpdateResolution = symbols.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)
        val itemUpdateMethod = itemUpdateResolution.valueOrNull()
        if (itemUpdateMethod != null) {
            val coordinator = runCatching {
                LyricsItemUpdateCoordinator(
                    installMethod = installMethod,
                    flags = ItemUpdateFlags(itemUpdateMethod.parameterTypes[2]),
                    seam = seam,
                    readyReplacementFor = readyReplacementFor,
                    isTracking = isTracking,
                    isFragmentUsable = fragmentUsable,
                    readyReapply = readyReapply,
                    logger = ModernXposedRuntime::log,
                )
            }.getOrNull()
            if (coordinator != null) {
                val itemUpdateHooked = runCatching {
                    ModernXposedRuntime.hookMethod(itemUpdateMethod, object : ModernMethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            itemUpdateContext.enterO2()
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val fragment = param.thisObject
                                val appleInvokedI2 = itemUpdateContext.appleInvokedI2DuringO2()
                                val flagsHolder = param.args.getOrNull(2)
                                itemUpdateContext.reentering {
                                    runCatching {
                                        fragment?.let { currentFragment ->
                                            coordinator.onItemUpdate(
                                                fragment = currentFragment,
                                                flagsHolder = flagsHolder,
                                                appleInvokedI2 = appleInvokedI2,
                                            )
                                        }
                                    }.onFailure { error ->
                                        ModernXposedRuntime.log(
                                            "custom lyrics item update hook failed: $error",
                                        )
                                    }
                                }
                            } finally {
                                itemUpdateContext.exitO2()
                            }
                        }
                    })
                }.isSuccess
                if (!itemUpdateHooked) {
                    ModernXposedRuntime.log(
                        "PlayerLyricsViewFragment.o2 could not be hooked; " +
                            itemUpdateResolution.summary,
                    )
                }
            }
        }
        val availabilityResolution = symbols.resolve(AppleMusicSymbols.LyricsAvailabilityPredicate)
        val availabilityMethod = availabilityResolution.valueOrNull()
        val availabilityHooked = availabilityMethod != null && runCatching {
            availabilityMethod.isAccessible = true
            ModernXposedRuntime.hookMethod(availabilityMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val nativeLyricsAvailable = param.result as? Boolean ?: return@runCatching
                        if (nativeLyricsAvailable) return@runCatching
                        val appleMusicId = seam.detailsOfItem(param.args.getOrNull(0))?.appleMusicId
                        appleMusicId?.let { id ->
                            session.ensureRequested(id)
                            autoSession?.ensureRequested(id)
                        }
                        val replacementReady = appleMusicId != null &&
                            (session.replacementOrPrepareFor(appleMusicId) != null ||
                                autoSession?.replacementOrPrepareFor(appleMusicId) != null)
                        val replacementPending = appleMusicId != null &&
                            (session.isTracking(appleMusicId) || autoSession?.isTracking(appleMusicId) == true)
                        if (
                            shouldExposeCustomLyrics(
                                nativeLyricsAvailable = nativeLyricsAvailable,
                                appleMusicId = appleMusicId,
                                replacementReady = replacementReady,
                                replacementPending = replacementPending,
                            )
                        ) {
                            param.result = true
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics availability hook failed: $error")
                    }
                }
            })
        }.isSuccess
        session.start()
        CurrentLyricsSourceStatus.installRefreshHandler { id ->
            if (currentSong.current()?.details?.appleMusicId == id && autoSession != null && !session.isMapped(id)) {
                mainHandler.post {
                    activeFragment?.get()?.let { readyReapply.recordMiss(it, id) }
                    autoSession.refreshCurrent(id)
                }
                true
            } else false
        }
        currentSong.addListener { current ->
            val appleMusicId = current?.details?.appleMusicId
            ModernXposedRuntime.log(
                "Desktop Lyrics song id=${appleMusicId ?: 0L} " +
                    "auto=${autoSession != null} " +
                    "suppressed=${appleMusicId in (autoLyricsRuntime?.suppressedIds ?: emptySet())} " +
                    "manual=${appleMusicId in configuredManualIds} " +
                    "mapped=${appleMusicId?.let(session::isMapped) ?: false}",
            )
            appleMusicId?.let(session::ensureRequested)
            autoSession?.onSongChanged(appleMusicId)
            appleMusicId?.let { id ->
                ModernXposedRuntime.log(
                    "Desktop Lyrics eligibility id=$id manualReady=${session.readyReplacementFor(id) != null} " +
                        "manualMapped=${session.isMapped(id)}",
                )
                if (session.readyReplacementFor(id) == null) autoSession?.ensureRequested(id)
            }
        }
        if (!availabilityHooked) {
            return TargetCapabilityInstall.Degraded(
                "Custom lyric I2 replacement installed, but unavailable-lyrics entry could not be enabled; " +
                    availabilityResolution.summary,
            )
        }
        return TargetCapabilityInstall.Active(
            "Custom lyric ID mappings installed" +
                (if (autoSession != null) " with Desktop Lyrics preferred auto TTML" else "") + "; " +
                listOf(
                    installMethodResolution.summary,
                    availabilityResolution.summary,
                    itemUpdateResolution.summary,
                    ptrResolution.summary,
                    nativeResolution.summary,
                    parserResolution.summary,
                    parseMethodResolution.summary,
                    "timingHooked=$parserHooked",
                    seam.fieldSummary.orEmpty(),
                ).joinToString("; "),
        )
    }

}

private fun itemString(item: Any, method: String): String? = runCatching {
    item.javaClass.getMethod(method).invoke(item) as? String
}.getOrNull()?.trim()?.takeIf(String::isNotEmpty)

private fun itemAlbum(item: Any): String? =
    itemString(item, "getAlbumName") ?: itemString(item, "getAlbumTitle") ?: runCatching {
        item.javaClass.getMethod("getAlbum").invoke(item)
    }.getOrNull()?.let { album ->
        (album as? String)?.trim()?.takeIf(String::isNotEmpty)
            ?: itemString(album, "getName") ?: itemString(album, "getTitle")
    } ?: runCatching {
        item.javaClass.getMethod("getAttributes").invoke(item)
    }.getOrNull()?.let { attributes -> itemString(attributes, "getAlbumName") }

private fun itemNumber(item: Any, method: String): Long? = runCatching {
    (item.javaClass.getMethod(method).invoke(item) as? Number)?.toLong()
}.getOrNull()?.takeIf { it > 0L }

internal fun selectLyricsInjectionAdamId(
    original: Any?,
    fragmentAdamId: Long?,
    publishedAdamId: Long?,
): Long? = if (
    original == null &&
    publishedAdamId != null &&
    publishedAdamId > 0L &&
    publishedAdamId != fragmentAdamId
) {
    publishedAdamId
} else {
    fragmentAdamId
}

/**
 * Apple emits a null SongInfoPtr when a playback item has no native lyrics,
 * and a live SongInfoPtr otherwise. Both forms can be recorded by the
 * ready-late reapply ledger while their custom replacement is preparing; the
 * ledger applies the same current-item and lifecycle gates to both.
 */
internal fun acceptsLyricsInstallArguments(args: Array<Any?>, ptrClass: Class<*>): Boolean =
    args.isNotEmpty() && (args[0] == null || ptrClass.isInstance(args[0]))

/**
 * Automatic lookup is fail-open for a missing native pointer and opt-in only
 * when the parser seam proved that the original document is not Word-timed or
 * is a foreign Word-timed document without a translation track. An unobserved
 * live pointer is left untouched rather than guessing.
 */
internal fun shouldTryAutoLyrics(
    original: Any?,
    metadata: TtmlDocumentMetadata?,
): Boolean = original == null ||
    shouldTryAutoLyricsForMetadata(metadata)

internal fun shouldTryAutoLyricsForMetadata(metadata: TtmlDocumentMetadata?): Boolean =
    metadata?.timingMode == TtmlTimingMode.NON_WORD || metadata?.needsTranslationFallback == true

internal fun shouldPrepareAutomaticLyrics(
    manualReplacement: Any?,
    autoEligible: Boolean,
): Boolean = manualReplacement == null && autoEligible

internal fun shouldExposeCustomLyrics(
    nativeLyricsAvailable: Boolean,
    appleMusicId: Long?,
    replacementReady: Boolean,
    replacementPending: Boolean = false,
): Boolean = nativeLyricsAvailable ||
    (appleMusicId != null && appleMusicId > 0L && (replacementReady || replacementPending))
