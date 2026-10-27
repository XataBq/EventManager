package ru.eventpass.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Block 06 semantic colors; status colors are independent from Material's error slot. */
object EventPassColors {
    val background = Color(0xFF0F131C)
    val card = Color(0xFF181B25)
    val surface = Color(0xFF1C1F29)
    val raised = Color(0xFF262A34)
    val accent = Color(0xFF8083FF)
    val accentLight = Color(0xFFC0C1FF)
    val onAccent = Color(0xFF0D0096)
    val textPrimary = Color(0xFFDFE2EF)
    val textSecondary = Color(0xFFC7C4D7)
    val muted = Color(0xFF908FA0)
    val success = Color(0xFF34D399)
    val warning = Color(0xFFFBBF24)
    val danger = Color(0xFFF87171)
    const val DisabledAlpha = 0.45f
    const val StatusAlpha = 0.15f
}

object EventPassDimens {
    val tiny = 4.dp
    val labelGap = 6.dp
    val small = 8.dp
    val iconGap = 10.dp
    val medium = 12.dp
    val fieldHorizontal = 14.dp
    val large = 16.dp
    val page = 24.dp
    val buttonHeight = 44.dp
    val compactHeight = 36.dp
    val touch = 48.dp
    val topBar = 56.dp
    val bottomBar = 64.dp
    val icon = 24.dp
    val avatar = 32.dp
    val statusMark = 72.dp
    val border = 1.dp
}

object EventPassShapes {
    val badge = RoundedCornerShape(4.dp)
    val control = RoundedCornerShape(8.dp)
    val section = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
}

@OptIn(ExperimentalTextApi::class)
private val Inter = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

object EventPassTypography {
    val heading = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.5.sp)
    val cardTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.1.sp)
    val emphasis = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.3.sp)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.3.sp)
    val label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.4.sp)
    val hint = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.4.sp)
    val tab = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.95.sp)
    val material = Typography(
        displayLarge = heading, displayMedium = heading, displaySmall = heading,
        headlineLarge = heading, headlineMedium = heading, headlineSmall = heading,
        titleLarge = cardTitle, titleMedium = emphasis, titleSmall = label,
        bodyLarge = body, bodyMedium = body, bodySmall = hint,
        labelLarge = emphasis, labelMedium = label, labelSmall = tab,
    )
}
