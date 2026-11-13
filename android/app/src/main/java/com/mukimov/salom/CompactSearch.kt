package com.mukimov.salom

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CompactSearch(value: String, onChange: (String) -> Unit, placeholder: String) {
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 14.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(Color(0xFFFFC400)),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .background(colors.surface, RoundedCornerShape(22.dp)).heightIn(min = 40.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        decorationBox = { innerField ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(18.dp)) {
                    val stroke = 1.7.dp.toPx()
                    drawCircle(colors.onSurfaceVariant, radius = size.width * .32f, center = Offset(size.width * .4f, size.height * .4f), style = Stroke(stroke))
                    drawLine(colors.onSurfaceVariant, Offset(size.width * .64f, size.height * .64f), Offset(size.width * .92f, size.height * .92f), strokeWidth = stroke)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = colors.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
                    innerField()
                }
            }
        }
    )
}
