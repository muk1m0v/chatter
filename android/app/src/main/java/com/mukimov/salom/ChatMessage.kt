package com.mukimov.salom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ChatMessage(message: Message, maxWidth: androidx.compose.ui.unit.Dp, media: MediaFiles, player: ChatAudioPlayer, grouped: Boolean) {
    val dark = MaterialTheme.colorScheme.background.red < .5f
    val textColor = if (message.mine) (if (dark) Color(0xFFFFF0BD) else Ink) else (if (dark) Color(0xFFECF1F6) else Ink)
    val bubbleColor = if (message.mine) (if (dark) Color(0xFF45452C) else Color(0xFFFFEDB2)) else (if (dark) Color(0xFF23313D) else Color.White)
    val emoji = message.attachment == null && VoicePolicy.emojiOnly(message.text)
    Row(Modifier.fillMaxWidth().padding(top = if (grouped) 1.dp else 5.dp), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
        if (emoji) Column(horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start) {
            Text(message.text, fontSize = 44.sp, lineHeight = 55.sp, modifier = Modifier.padding(horizontal = 6.dp))
            if (message.time.isNotEmpty()) MessageMeta(message.time, message.receipt, message.mine, dark, Modifier.clip(RoundedCornerShape(8.dp)).background(if (dark) Color(0x30202D38) else Color(0x20FFFFFF)).padding(horizontal = 5.dp, vertical = 1.dp))
        } else Column(Modifier.widthIn(max = maxWidth).clip(RoundedCornerShape(17.dp,17.dp,if (message.mine) 5.dp else 17.dp,if (message.mine) 17.dp else 5.dp)).background(bubbleColor).padding(horizontal=10.dp,vertical=6.dp)) {
            val attachment = message.attachment
            if (attachment != null) {
                if (attachment.mime.startsWith("audio/")) AudioBubble(attachment, player, textColor)
                else AttachmentBubble(attachment, media, textColor)
            }
            if (message.text.isNotEmpty() && attachment == null) {
                BubbleText(message.text, message.time, message.receipt, message.mine, dark, textColor, maxWidth - 20.dp)
            } else {
                if (message.text.isNotEmpty()) Text(message.text, color=textColor, fontSize=15.sp,lineHeight=20.sp,modifier=Modifier.padding(top=3.dp))
                if (message.time.isNotEmpty()) MessageMeta(message.time, message.receipt, message.mine, dark, Modifier.align(Alignment.End).padding(top=2.dp), textColor)
            }
        }
    }
}

@Composable
private fun BubbleText(text: String, time: String, receipt: String, mine: Boolean, dark: Boolean, color: Color, maxWidth: androidx.compose.ui.unit.Dp) {
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(fontSize=15.sp,lineHeight=20.sp)
    val width = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
    val textMeasure = remember(text,width,density.fontScale) { measure.measure(AnnotatedString(text), style, maxLines=2, constraints=Constraints(maxWidth=width)) }
    val clockWidth = measure.measure(AnnotatedString(time), TextStyle(fontSize=9.sp)).size.width
    val inline = !text.contains('\n') && textMeasure.lineCount == 1 && textMeasure.size.width + clockWidth + with(density) { (if (mine) 25.dp else 9.dp).roundToPx() } <= width
    if (inline) Row(verticalAlignment=Alignment.Bottom) {
        Text(text,color=color,style=style)
        if (time.isNotEmpty()) MessageMeta(time, receipt, mine, dark, Modifier.padding(start=7.dp,bottom=1.dp), color)
    } else Column {
        Text(text,color=color,style=style)
        if (time.isNotEmpty()) MessageMeta(time, receipt, mine, dark, Modifier.align(Alignment.End).padding(top=1.dp), color)
    }
}

@Composable
private fun MessageMeta(time: String, receipt: String, mine: Boolean, dark: Boolean, modifier: Modifier = Modifier, contentColor: Color = if (dark) Color(0xFFCAD6DD) else Color(0xFF71776B)) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(time, fontSize = 9.sp, lineHeight = 11.sp, color = contentColor.copy(alpha = .66f))
        if (mine) {
            Spacer(Modifier.width(3.dp))
            when (receipt) {
                "pending" -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.dp, color = contentColor.copy(alpha = .72f))
                "read" -> Text("✓✓", fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF38C878))
                "delivered" -> Text("✓✓", fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Medium, color = contentColor.copy(alpha = .78f))
                else -> Text("✓", fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Medium, color = contentColor.copy(alpha = .72f))
            }
        }
    }
}
