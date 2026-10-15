package com.example.airkast.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** シェイプトークン。大きめの角丸で統一する。 */
val AirkastShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** 完全な丸(ピル/円)。ボタン・チップ用。 */
val PillShape: Shape = CircleShape

/** 4dp グリッドのスペーシングトークン。 */
object Spacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** タッチターゲットの最小サイズ(アクセシビリティ)。 */
val MinTouchTarget: Dp = 48.dp

/**
 * グルーブ型リストの項目形状。先頭/末尾は大きく、中間は小さく丸める。
 * 同じコンテナ色の項目を 2dp 間隔で並べると、一つの塊として読める。
 */
fun groupedItemShape(index: Int, count: Int): Shape {
    val outer = 20.dp
    val inner = 6.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}
