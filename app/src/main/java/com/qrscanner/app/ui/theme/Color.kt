package com.qrscanner.app.ui.theme

import androidx.compose.ui.graphics.Color

// Primary Colors - Light Orange
val PrimaryOrange = Color(0xFFFF9F43)
val PrimaryOrangeLight = Color(0xFFFFBE76)
val PrimaryOrangeDark = Color(0xFFE67E22)

// Keep old names for backward compatibility but point to new colors
val PrimaryBlue = PrimaryOrange
val PrimaryBlueLight = PrimaryOrangeLight
val PrimaryBlueDark = PrimaryOrangeDark

// Accent Colors
val AccentCoral = Color(0xFFFF6B6B)

// Darker coral used specifically for the bell unread badge so
// white 10sp text passes WCAG AA (4.5:1 small text). #DC2626
// (red-600) has relative luminance ~0.167 (recomputed from sRGB
// → linear → coefficients: 0.2126·0.715 + 0.7152·0.0194 +
// 0.0722·0.0194 = 0.167), giving white contrast (1.05)/(0.217)
// = 4.83:1. A prior comment claimed 5.43:1 — that was a math
// error caught in QC; 4.83:1 still passes the 4.5:1 AA-small
// threshold so the badge readability is unaffected. Kept
// separate from AccentCoral so non-badge usages preserve the
// brand's friendlier coral tone.
val AccentCoralDark = Color(0xFFDC2626)

val AccentMint = Color(0xFF4ECDC4)
val AccentGold = Color(0xFFFFD93D)

// Neutral Colors
val BackgroundWhite = Color(0xFFFAFAFA)
val SurfaceWhite = Color(0xFFFFFFFF)
val CardBackground = Color(0xFFF5F7FA)
val RowBackground = Color(0xFFF7F8FA)
val DisabledBackground = Color(0xFFF1F3F5)
val DisabledContent = Color(0xFFCBD0D6)
val GradientPeach = Color(0xFFFFF8F0)

// Text Colors
val TextPrimary = Color(0xFF1A1A2E)
val TextSecondary = Color(0xFF6B7280)
val TextTertiary = Color(0xFF9CA3AF)

// Success/Error Colors
val SuccessGreen = Color(0xFF10B981)
val ErrorRed = Color(0xFFEF4444)
val WarningAmber = Color(0xFFF59E0B)

// Scanner Overlay
val ScannerOverlay = Color(0x99000000)
val ScannerFrame = Color(0xFFFF9F43)
