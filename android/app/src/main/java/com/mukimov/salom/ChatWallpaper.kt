package com.mukimov.salom

import androidx.annotation.DrawableRes

@DrawableRes
internal fun chatWallpaperResource(id: String): Int = when (id) {
    "garden" -> R.drawable.chat_wallpaper_garden
    "indigo" -> R.drawable.chat_wallpaper_indigo
    else -> R.drawable.chat_wallpaper_midnight
}
