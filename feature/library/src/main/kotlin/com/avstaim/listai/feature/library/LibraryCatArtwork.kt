package com.avstaim.listai.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale

/** Original vector paths, drawn at the requested size. No bitmap or raster sprite is involved. */
@Composable
internal fun LibraryCatArtwork(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    eyeOpening: Float = 0f,
    tailSwing: Float = 0f,
) {
    val art = remember { CatVectors() }
    val fur = if (darkTheme) Color(0xFFA1ADB7) else Color(0xFF444D57)
    val shade = if (darkTheme) Color(0xFF6E7B87) else Color(0xFF2C343E)
    val stripe = if (darkTheme) Color(0xFF505E6B) else Color(0xFF202833)
    val muzzle = if (darkTheme) Color(0xFFBBC4CB) else Color(0xFF7C8791)
    val warmEar = if (darkTheme) Color(0xFF958E91) else Color(0xFF776B70)
    Canvas(modifier) {
        scale(size.width / 256f, size.height / 150f, pivot = Offset.Zero) {
            drawOval(shade.copy(alpha = .14f), Offset(28f, 92f), Size(190f, 10f))
            rotate(tailSwing.coerceIn(-1f, 1f) * 13f, Offset(213f, 78f)) {
                drawPath(art.tail, shade)
                art.tailStripes.forEach { drawPath(it, stripe, style = Stroke(4.7f, cap = StrokeCap.Round)) }
            }
            drawPath(art.body, fur)
            drawPath(art.haunch, shade.copy(alpha = .42f))
            art.bodyStripes.forEach { drawPath(it, stripe.copy(alpha = .75f), style = Stroke(5.5f, cap = StrokeCap.Round)) }
            drawPath(art.paws, fur)
            drawPath(art.pawFold, shade, style = Stroke(2f, cap = StrokeCap.Round))
            drawPath(art.head, fur)
            drawPath(art.leftEar, warmEar)
            drawPath(art.rightEar, warmEar)
            art.faceStripes.forEach { drawPath(it, stripe, style = Stroke(2.6f, cap = StrokeCap.Round)) }
            drawOval(muzzle.copy(alpha = .62f), Offset(46f, 68f), Size(24f, 20f))
            drawOval(muzzle.copy(alpha = .62f), Offset(65f, 68f), Size(22f, 20f))
            drawPath(art.nose, stripe)
            drawPath(art.mouth, stripe, style = Stroke(1.7f, cap = StrokeCap.Round))
            drawPath(art.leftEye, stripe, style = Stroke(2.8f, cap = StrokeCap.Round))
            if (eyeOpening > .05f) {
                drawOval(Color(0xFFA8AD82), Offset(75f, 60f - 3f * eyeOpening), Size(12f, 6f * eyeOpening))
                drawLine(stripe, Offset(81f, 58f), Offset(81f, 64f), 1.8f)
            }
            drawPath(art.rightEye, stripe, style = Stroke(2.8f, cap = StrokeCap.Round))
            art.whiskers.forEach { drawPath(it, muzzle.copy(alpha = .85f), style = Stroke(1f, cap = StrokeCap.Round)) }
        }
    }
}

private class CatVectors {
    val tail = path {
        moveTo(199f, 70f); cubicTo(229f, 63f, 244f, 81f, 239f, 102f)
        cubicTo(236f, 118f, 228f, 137f, 213f, 146f)
        cubicTo(204f, 151f, 197f, 141f, 206f, 135f)
        cubicTo(219f, 125f, 221f, 110f, 222f, 99f)
        cubicTo(224f, 88f, 217f, 85f, 204f, 88f); close()
    }
    val body = path {
        moveTo(77f, 87f); cubicTo(86f, 64f, 112f, 40f, 153f, 40f)
        cubicTo(180f, 39f, 204f, 47f, 216f, 65f)
        cubicTo(228f, 83f, 213f, 98f, 182f, 99f)
        lineTo(91f, 98f); cubicTo(76f, 99f, 69f, 95f, 77f, 87f); close()
    }
    val haunch = path {
        moveTo(149f, 54f); cubicTo(190f, 45f, 215f, 59f, 211f, 78f)
        cubicTo(208f, 96f, 177f, 97f, 159f, 94f)
        cubicTo(181f, 91f, 189f, 79f, 177f, 68f)
        cubicTo(169f, 61f, 160f, 59f, 149f, 54f); close()
    }
    val paws = path {
        moveTo(47f, 89f); cubicTo(70f, 83f, 105f, 84f, 124f, 90f)
        cubicTo(133f, 93f, 131f, 100f, 120f, 101f)
        lineTo(44f, 101f); cubicTo(35f, 99f, 36f, 92f, 47f, 89f); close()
    }
    val pawFold = path { moveTo(78f, 94f); cubicTo(93f, 92f, 105f, 94f, 110f, 99f) }
    val head = path {
        moveTo(35f, 48f); cubicTo(30f, 40f, 25f, 22f, 30f, 18f)
        cubicTo(34f, 17f, 50f, 28f, 55f, 34f)
        cubicTo(65f, 29f, 77f, 29f, 84f, 33f)
        cubicTo(89f, 25f, 98f, 10f, 103f, 14f)
        cubicTo(108f, 18f, 103f, 41f, 100f, 47f)
        cubicTo(108f, 65f, 100f, 87f, 84f, 94f)
        cubicTo(70f, 100f, 47f, 95f, 38f, 82f)
        cubicTo(31f, 73f, 28f, 60f, 35f, 48f); close()
    }
    val leftEar = path { moveTo(33f, 27f); lineTo(48f, 37f); lineTo(37f, 46f); close() }
    val rightEar = path { moveTo(99f, 24f); lineTo(96f, 43f); lineTo(88f, 37f); close() }
    val nose = path { moveTo(60f, 72f); quadraticTo(67f, 69f, 74f, 72f); lineTo(67f, 78f); close() }
    val mouth = path { moveTo(67f, 78f); lineTo(66f, 82f); quadraticTo(61f, 86f, 57f, 81f); moveTo(66f, 82f); quadraticTo(72f, 86f, 77f, 81f) }
    val leftEye = path { moveTo(43f, 62f); quadraticTo(50f, 66f, 58f, 62f) }
    val rightEye = path { moveTo(75f, 61f); quadraticTo(81f, 64f, 88f, 59f) }
    val bodyStripes = listOf(
        path { moveTo(107f, 55f); cubicTo(112f, 62f, 112f, 69f, 107f, 76f) },
        path { moveTo(121f, 46f); cubicTo(128f, 52f, 131f, 63f, 126f, 72f) },
        path { moveTo(139f, 43f); cubicTo(145f, 48f, 150f, 56f, 148f, 62f) },
        path { moveTo(160f, 43f); cubicTo(164f, 48f, 168f, 52f, 170f, 57f) },
        path { moveTo(181f, 48f); cubicTo(184f, 54f, 189f, 58f, 195f, 59f) },
        path { moveTo(208f, 65f); quadraticTo(201f, 70f, 192f, 69f) },
        path { moveTo(215f, 79f); quadraticTo(205f, 80f, 200f, 86f) },
    )
    val faceStripes = listOf(
        path { moveTo(53f, 38f); lineTo(57f, 49f); lineTo(62f, 43f); lineTo(67f, 51f); lineTo(71f, 40f) },
        path { moveTo(81f, 37f); lineTo(78f, 48f) },
        path { moveTo(33f, 56f); lineTo(42f, 59f) },
        path { moveTo(34f, 68f); lineTo(45f, 70f) },
        path { moveTo(96f, 52f); lineTo(89f, 56f) },
        path { moveTo(100f, 67f); lineTo(91f, 70f) },
        path { moveTo(95f, 80f); lineTo(86f, 81f) },
    )
    val tailStripes = listOf(
        path { moveTo(225f, 86f); lineTo(237f, 89f) },
        path { moveTo(224f, 102f); lineTo(235f, 106f) },
        path { moveTo(219f, 117f); lineTo(229f, 122f) },
        path { moveTo(211f, 132f); lineTo(218f, 139f) },
    )
    val whiskers = listOf(
        path { moveTo(53f, 78f); lineTo(26f, 74f) },
        path { moveTo(53f, 82f); lineTo(29f, 86f) },
        path { moveTo(80f, 77f); lineTo(105f, 70f) },
        path { moveTo(81f, 81f); lineTo(105f, 83f) },
    )
}

private fun path(block: Path.() -> Unit): Path = Path().apply(block)
