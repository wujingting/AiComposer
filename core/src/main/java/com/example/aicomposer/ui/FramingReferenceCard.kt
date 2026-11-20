package com.example.aicomposer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.example.aicomposer.ai.FramingReference
import com.example.aicomposer.i18n.tr

/** A still from the moment AI framing was requested, kept separate from the moving viewfinder. */
@Composable
fun FramingReferenceCard(reference: FramingReference, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val image = remember(reference.image) { reference.image.asImageBitmap() }
    val close = tr("收起构图参考", "構図見本を閉じる", "Close framing reference")
    val shape = RoundedCornerShape(6.dp)
    BoxWithConstraints(modifier) {
        val width = minOf(maxWidth * 0.38f, 164.dp)
        val height = minOf(width * image.height / image.width, maxHeight * 0.32f)
        Column(
            Modifier.width(width).clip(shape).background(CoachColors.StripBg)
                .border(1.dp, CoachColors.Muted, shape)
                .clickable(role = Role.Button, onClickLabel = close, onClick = onDismiss)
                .padding(3.dp),
        ) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxWidth().height(height), contentScale = ContentScale.Fit)
            Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("构图参考", "構図見本", "Reference"), modifier = Modifier.weight(1f),
                    color = CoachColors.Paper, fontSize = CoachType.Caption,
                )
                Text("×", modifier = Modifier.clearAndSetSemantics { }, color = CoachColors.Paper, fontSize = CoachType.Title)
            }
        }
    }
}
