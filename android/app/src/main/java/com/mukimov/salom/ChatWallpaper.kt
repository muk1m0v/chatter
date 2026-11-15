package com.mukimov.salom

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal fun Modifier.chatWallpaper(dark: Boolean): Modifier = drawWithCache {
    val pattern = Path()
    val tile = 86.dp.toPx()
    val radius = 9.dp.toPx()
    var row = 0
    var y = 20.dp.toPx()
    while (y < size.height) {
        var x = if (row % 2 == 0) 20.dp.toPx() else 61.dp.toPx()
        while (x < size.width) {
            pattern.moveTo(x-radius,y-radius*.65f); pattern.lineTo(x+radius,y-radius*.65f); pattern.lineTo(x+radius,y+radius*.45f); pattern.lineTo(x,y+radius*.45f); pattern.lineTo(x-radius*.6f,y+radius); pattern.lineTo(x-radius*.5f,y+radius*.45f); pattern.lineTo(x-radius,y+radius*.45f); pattern.close()
            val starX=x+tile*.45f; val starY=y+tile*.48f
            pattern.moveTo(starX-3.dp.toPx(),starY); pattern.lineTo(starX+3.dp.toPx(),starY)
            pattern.moveTo(starX,starY-3.dp.toPx()); pattern.lineTo(starX,starY+3.dp.toPx())
            x += tile
        }
        row++; y += tile
    }
    onDrawBehind { drawPath(pattern,if(dark) Color.White.copy(alpha=.025f) else Color(0xFF69705A).copy(alpha=.045f),style=Stroke(.7.dp.toPx())) }
}
