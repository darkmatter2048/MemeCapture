package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.model.AppColorTheme
import com.example.model.AppTheme

@Composable
fun MyApplicationTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    colorTheme: AppColorTheme = AppColorTheme.GREEN,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (appTheme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when (colorTheme) {
        AppColorTheme.GREEN -> if (darkTheme) greenDark() else greenLight()
        AppColorTheme.BLUE -> if (darkTheme) blueDark() else blueLight()
        AppColorTheme.PURPLE -> if (darkTheme) purpleDark() else purpleLight()
        AppColorTheme.ORANGE -> if (darkTheme) orangeDark() else orangeLight()
        AppColorTheme.PINK -> if (darkTheme) pinkDark() else pinkLight()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// --- FRESH GREEN ---
private fun greenLight() = lightColorScheme(
    primary = FreshGreenPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F397),
    onPrimaryContainer = Color(0xFF0D2000),
    secondary = Color(0xFF55624C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E7CB),
    onSecondaryContainer = Color(0xFF131F0E),
    background = FreshGreenBackground,
    onBackground = Color(0xFF1A1C18),
    surface = FreshGreenSurface,
    onSurface = Color(0xFF1A1C18),
    surfaceVariant = Color(0xFFE0E4D6),
    onSurfaceVariant = Color(0xFF44483D),
    outline = FreshGreenOutline
)

private fun greenDark() = darkColorScheme(
    primary = FreshGreenPrimaryDark,
    onPrimary = Color(0xFF1B3700),
    primaryContainer = Color(0xFF295000),
    onPrimaryContainer = Color(0xFFB8F397),
    secondary = Color(0xFFBDCBB0),
    onSecondary = Color(0xFF283421),
    secondaryContainer = Color(0xFF3E4A36),
    onSecondaryContainer = Color(0xFFD9E7CB),
    background = FreshGreenBackgroundDark,
    onBackground = Color(0xFFE2E3DE),
    surface = FreshGreenSurfaceDark,
    onSurface = Color(0xFFE2E3DE),
    surfaceVariant = Color(0xFF44483D),
    onSurfaceVariant = Color(0xFFC4C8BA),
    outline = Color(0xFF8E9285)
)

// --- SKY BLUE ---
private fun blueLight() = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF73777F)
)

private fun blueDark() = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8D9199)
)

// --- ELEGANT PURPLE ---
private fun purpleLight() = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EB),
    onSurfaceVariant = Color(0xFF49454E),
    outline = Color(0xFF79747E)
)

private fun purpleDark() = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4CF),
    outline = Color(0xFF938F99)
)

// --- VIBRANT ORANGE ---
private fun orangeLight() = lightColorScheme(
    primary = Color(0xFF8B5000),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2D1600),
    secondary = Color(0xFF715A41),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDDABE),
    onSecondaryContainer = Color(0xFF281805),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF201A17),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF201A17),
    surfaceVariant = Color(0xFFF4E0D4),
    onSurfaceVariant = Color(0xFF52443C),
    outline = Color(0xFF827568)
)

private fun orangeDark() = darkColorScheme(
    primary = Color(0xFFFFB870),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693B00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFDFC2A2),
    onSecondary = Color(0xFF402D17),
    secondaryContainer = Color(0xFF58422B),
    onSecondaryContainer = Color(0xFFFDDABE),
    background = Color(0xFF201A17),
    onBackground = Color(0xFFEBE0DB),
    surface = Color(0xFF201A17),
    onSurface = Color(0xFFEBE0DB),
    surfaceVariant = Color(0xFF52443C),
    onSurfaceVariant = Color(0xFFD7C2B9),
    outline = Color(0xFF9B8E83)
)

// --- SAKURA PINK ---
private fun pinkLight() = lightColorScheme(
    primary = Color(0xFF9C4146),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD9),
    onPrimaryContainer = Color(0xFF40000A),
    secondary = Color(0xFF775656),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD9),
    onSecondaryContainer = Color(0xFF2C1516),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF201A1A),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF201A1A),
    surfaceVariant = Color(0xFFF5DDDD),
    onSurfaceVariant = Color(0xFF534343),
    outline = Color(0xFF857373)
)

private fun pinkDark() = darkColorScheme(
    primary = Color(0xFFFFB3B4),
    onPrimary = Color(0xFF5F121C),
    primaryContainer = Color(0xFF7D2930),
    onPrimaryContainer = Color(0xFFFFDAD9),
    secondary = Color(0xFFE7BDBE),
    onSecondary = Color(0xFF44292A),
    secondaryContainer = Color(0xFF5D3F3F),
    onSecondaryContainer = Color(0xFFFFDAD9),
    background = Color(0xFF201A1A),
    onBackground = Color(0xFFECE0DF),
    surface = Color(0xFF201A1A),
    onSurface = Color(0xFFECE0DF),
    surfaceVariant = Color(0xFF534343),
    onSurfaceVariant = Color(0xFFD8C2C2),
    outline = Color(0xFF9F8C8C)
)
