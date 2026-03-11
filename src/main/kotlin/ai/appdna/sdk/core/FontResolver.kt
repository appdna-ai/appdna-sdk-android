package ai.appdna.sdk.core

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Resolves platform-native font families from the cross-platform font value stored in config.
 * Only fonts natively available on Android are supported — no custom font downloading.
 */
object FontResolver {

    fun resolve(fontFamily: String?): FontFamily {
        return when (fontFamily) {
            // System
            "system", "-apple-system", "BlinkMacSystemFont", "sans-serif", "Roboto" -> FontFamily.Default
            "system-serif", "serif" -> FontFamily.Serif
            "system-mono", "monospace", "SF Mono" -> FontFamily.Monospace
            // Sans-Serif mapped to Android equivalents
            "helvetica-neue", "Helvetica Neue", "avenir", "Avenir",
            "gill-sans", "Gill Sans", "verdana", "Verdana",
            "arial", "Arial", "trebuchet", "Trebuchet MS" -> FontFamily.Default
            "avenir-next", "Avenir Next", "futura", "Futura" -> FontFamily.Default
            // Serif
            "georgia", "Georgia", "times", "Times New Roman",
            "palatino", "Palatino", "baskerville", "Baskerville",
            "didot", "Didot" -> FontFamily.Serif
            // Monospace
            "courier-new", "Courier New", "menlo", "Menlo" -> FontFamily.Monospace
            // Display / cursive
            "snell", "Snell Roundhand" -> FontFamily.Cursive
            "chalkboard", "Chalkboard SE", "noteworthy", "Noteworthy" -> FontFamily.Cursive
            "copperplate", "Copperplate" -> FontFamily.Serif
            else -> FontFamily.Default
        }
    }

    fun fontWeight(weight: Int?): FontWeight {
        return when (weight ?: 400) {
            in 0..199 -> FontWeight.Thin
            in 200..299 -> FontWeight.ExtraLight
            in 300..399 -> FontWeight.Light
            in 400..499 -> FontWeight.Normal
            in 500..599 -> FontWeight.Medium
            in 600..699 -> FontWeight.SemiBold
            in 700..799 -> FontWeight.Bold
            in 800..899 -> FontWeight.ExtraBold
            else -> FontWeight.Black
        }
    }
}
