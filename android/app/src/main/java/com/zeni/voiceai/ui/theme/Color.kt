package com.zeni.voiceai.ui.theme

import androidx.compose.ui.graphics.Color

// Zeni Premium Identity (Dark/Neon)
val ZeniBlack = Color(0xFF0A0A0A)
val ZeniDarkGray = Color(0xFF141414)
val ZeniSurface = Color(0xFF1E1E1E)

// Accents (Neon/Glow)
val ZeniBlue = Color(0xFF2979FF)
val ZeniCyan = Color(0xFF00E5FF)
val ZeniPurple = Color(0xFFD500F9)
val ZeniGreen = Color(0xFF00E676)
val ZeniOrange = Color(0xFFFF9100)

// Character Colors (Anime/Hybrid Palette)
val SkinHighlight = Color(0xFFFFF0E5) // Porceline skin
val SkinBase = Color(0xFFFFDAB9)      // Peach/Fair
val SkinShadow = Color(0xFFE6B8A2)    // Soft blush shadow
val SkinDeepShadow = Color(0xFFC58E7F) // Neck shadow

// Anime Eyes (Jewel-like)
val AnimeEyeTop = Color(0xFF1A237E)   // Deep Indigo
val AnimeEyeBottom = Color(0xFF4FC3F7) // Cyan/Light Blue
val AnimeEyeHighlight = Color.White

// Anime Hair (Styled)
val HairDark = Color(0xFF101010)      // Jet Black/Blue
val HairBase = Color(0xFF263238)      // Dark slate
val HairHighlight = Color(0xFF546E7A) // Blue-grey sheen

val LipRose = Color(0xFFFF8A80)       // Soft rose

// Missing definitions for Handsome Character
val SkinMid = Color(0xFFF0C8A0)       // Mid-tone skin
val LipBase = Color(0xFFDCA8A8)       // Natural lip
val LipShadow = Color(0xFF8E4444)     // Inner lip shadow

// State Colors
val StateListening = ZeniGreen
val StateThinking = ZeniPurple
val StateSpeaking = ZeniBlue
val StateError = Color(0xFFFF1744)

// Legacy mapping for compatibility
val Primary = ZeniBlue
val PrimaryDark = Color(0xFF0D47A1)
val Background = ZeniBlack
val Surface = ZeniSurface
val OnPrimary = Color.White
val OnBackground = Color.White
val OnSurface = Color(0xFFEEEEEE)

val Listening = StateListening
val Processing = StateThinking
val Speaking = StateSpeaking
val Error = StateError
val Disconnected = Color(0xFF757575)

// The character will use raw colors, but we map theme slots
val PrimaryDarkTheme = ZeniBlue
val BackgroundDark = ZeniBlack
val SurfaceDark = ZeniSurface
val OnBackgroundDark = Color(0xFFF0F0F0)
val OnSurfaceDark = Color(0xFFF0F0F0)
