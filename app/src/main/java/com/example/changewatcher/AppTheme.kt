package com.example.changewatcher

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * AppTheme.kt
 *
 * Ajustes de apariencia y la función que arma los colores de la app a partir de ellos.
 *
 * Hay dos formas de elegir los colores:
 *  - Dinámico: los colores del teléfono (Material You, solo en Android 12 o superior).
 *  - Manual: un color a elegir entre varios predefinidos.
 * Aparte, el modo claro u oscuro puede seguir al teléfono o fijarse a mano.
 */

/** Modo claro u oscuro de la app. */
enum class ThemeMode(val label: String) {
    SYSTEM("Sistema"),
    LIGHT("Claro"),
    DARK("Oscuro"),
}

/**
 * Ajustes de apariencia elegidos por el usuario.
 *
 * @param dynamic true para usar los colores del teléfono (si el teléfono lo permite).
 * @param accent id del color elegido en el tema manual (ver [ACCENTS]).
 * @param mode modo claro, oscuro o el del sistema.
 */
data class ThemeSettings(
    val dynamic: Boolean = true,
    val accent: String = "azul",
    val mode: ThemeMode = ThemeMode.SYSTEM,
)

/**
 * Color del tema manual. Cada uno trae un color principal y uno de contraste (terciario, que se usa
 * para resaltar los departamentos elegidos), en versión clara y oscura.
 */
class Accent(
    val id: String,
    val label: String,
    val primaryLight: Color,
    val primaryDark: Color,
    val tertiaryLight: Color,
    val tertiaryDark: Color,
)

/** Colores disponibles en el tema manual. */
val ACCENTS: List<Accent> = listOf(
    Accent("azul", "Azul", Color(0xFF1565C0), Color(0xFF90CAF9), Color(0xFF00897B), Color(0xFF80CBC4)),
    Accent("verde", "Verde", Color(0xFF2E7D32), Color(0xFFA5D6A7), Color(0xFFEF6C00), Color(0xFFFFCC80)),
    Accent("rojo", "Rojo", Color(0xFFC62828), Color(0xFFEF9A9A), Color(0xFF1565C0), Color(0xFF90CAF9)),
    Accent("naranja", "Naranja", Color(0xFFE65100), Color(0xFFFFB74D), Color(0xFF00695C), Color(0xFF80CBC4)),
    Accent("morado", "Morado", Color(0xFF6A1B9A), Color(0xFFCE93D8), Color(0xFF00838F), Color(0xFF80DEEA)),
)

/** Arma los colores de la app según los ajustes de apariencia (y el modo del teléfono si corresponde). */
@Composable
fun appColorScheme(t: ThemeSettings): ColorScheme {
    val ctx = LocalContext.current
    val dark = when (t.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    if (t.dynamic && Build.VERSION.SDK_INT >= 31) {
        return if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    }
    val a = ACCENTS.firstOrNull { it.id == t.accent } ?: ACCENTS[0]
    return if (dark) {
        darkColorScheme(
            primary = a.primaryDark, onPrimary = Color.Black,
            tertiary = a.tertiaryDark, onTertiary = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = a.primaryLight, onPrimary = Color.White,
            tertiary = a.tertiaryLight, onTertiary = Color.White,
        )
    }
}
