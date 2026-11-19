package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme =
  lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = PureWhite,
    primaryContainer = MintContainer,
    onPrimaryContainer = OnMintContainer,
    secondary = EmeraldBright,
    onSecondary = PureWhite,
    secondaryContainer = MintSurface,
    onSecondaryContainer = EmeraldPrimaryDark,
    background = StudioDeskLight,
    onBackground = SlateInk,
    surface = PureWhite,
    onSurface = SlateInk,
    surfaceVariant = MintSurface,
    onSurfaceVariant = MutedSlate,
    outline = SoftBorderLight,
    error = ErrorRed,
    onError = PureWhite,
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = EmeraldDarkPrimary,
    onPrimary = OnEmeraldDark,
    primaryContainer = Color(0xFF065F46),
    onPrimaryContainer = CrispOffWhite,
    secondary = EmeraldBright,
    onSecondary = OnEmeraldDark,
    secondaryContainer = StudioCardDark,
    onSecondaryContainer = CrispOffWhite,
    background = StudioDeskDark,
    onBackground = CrispOffWhite,
    surface = StudioSurfaceDark,
    onSurface = CrispOffWhite,
    surfaceVariant = StudioCardDark,
    onSurfaceVariant = MutedMintText,
    outline = SoftBorderDark,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
  )

val StudioShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun PdfStudioTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    shapes = StudioShapes,
    content = content,
  )
}
