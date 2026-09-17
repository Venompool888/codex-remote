package app.codexremote.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.R

object AppColors {
    val background: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_background)
    val surface: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_surface)
    val surfaceContainerLow: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_surface_container_low)
    val surfaceContainer: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_surface_container)
    val surfaceContainerHigh: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_surface_container_high)
    val surfaceContainerHighest: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_surface_container_highest)
    val onSurface: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_on_surface)
    val onSurfaceVariant: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_on_surface_variant)
    val onSurfaceMuted: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_on_surface_muted)
    val outline: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_outline)
    val outlineVariant: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_outline_variant)
    val primary: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_primary)
    val onPrimary: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_on_primary)
    val primaryContainer: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_primary_container)
    val onPrimaryContainer: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_on_primary_container)
    val userBubble: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_user_bubble)
    val error: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_error)
    val errorContainer: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_error_container)
    val warning: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_warning)
    val success: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_success)
    val running: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_running)
    val drawerScrim: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_drawer_scrim)
    val composer: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_composer)
    val inlineCodeBackground: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_inline_code_background)
    val skeleton: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.app_skeleton)
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    )
)

@Composable
fun CodexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = AppColors.primary,
            onPrimary = AppColors.onPrimary,
            primaryContainer = AppColors.primaryContainer,
            onPrimaryContainer = AppColors.onPrimaryContainer,
            surface = AppColors.surface,
            onSurface = AppColors.onSurface,
            surfaceVariant = AppColors.surfaceContainerHigh,
            onSurfaceVariant = AppColors.onSurfaceVariant,
            outline = AppColors.outline,
            outlineVariant = AppColors.outlineVariant,
            error = AppColors.error,
            onError = Color.White,
            background = AppColors.background,
            onBackground = AppColors.onSurface
        )
    } else {
        lightColorScheme(
            primary = AppColors.primary,
            onPrimary = AppColors.onPrimary,
            primaryContainer = AppColors.primaryContainer,
            onPrimaryContainer = AppColors.onPrimaryContainer,
            surface = AppColors.surface,
            onSurface = AppColors.onSurface,
            surfaceVariant = AppColors.surfaceContainerHigh,
            onSurfaceVariant = AppColors.onSurfaceVariant,
            outline = AppColors.outline,
            outlineVariant = AppColors.outlineVariant,
            error = AppColors.error,
            onError = Color.White,
            background = AppColors.background,
            onBackground = AppColors.onSurface
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
