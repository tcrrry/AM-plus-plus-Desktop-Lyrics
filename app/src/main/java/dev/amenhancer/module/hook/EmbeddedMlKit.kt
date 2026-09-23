package dev.amenhancer.module.hook

import android.content.Context
import com.google.mlkit.common.internal.CommonComponentRegistrar
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar

/** NPatch embeds the module dex but does not merge its ML Kit manifest service. */
internal object EmbeddedMlKit {
    @Synchronized fun initialize(context: Context) {
        MlKitContext.initializeIfNeeded(
            context.applicationContext,
            listOf(
                CommonComponentRegistrar(),
                NaturalLanguageTranslateRegistrar(),
            ),
        )
    }
}
