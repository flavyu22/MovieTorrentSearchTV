package io.github.flavyu22.movietorrentsearchtv.ui.locals

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.flavyu22.movietorrentsearchtv.model.Translations

val LocalAppLanguage = staticCompositionLocalOf { "EN" }
val LocalTranslationStrings = staticCompositionLocalOf { Translations["EN"]!! }
