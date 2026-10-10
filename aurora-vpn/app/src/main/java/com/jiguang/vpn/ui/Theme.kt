package com.jiguang.vpn.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import kotlin.math.roundToInt

/** 深色仪表盘主题(首页与线路子页共用)。 */
object Theme {

    // 背景 / 卡片
    val BG = Color.rgb(0x0A, 0x0E, 0x1A)
    val CARD = Color.rgb(0x14, 0x1A, 0x2B)
    val CARD2 = Color.rgb(0x1D, 0x25, 0x3C)
    val SELECTED_BG = Color.rgb(0x22, 0x2E, 0x50)

    // 品牌色
    val PRIMARY = Color.rgb(0x4D, 0x7C, 0xFE)
    val PRIMARY_DARK = Color.rgb(0x3D, 0x5A, 0xFE)
    val ACCENT = Color.rgb(0x7C, 0x4D, 0xFF)

    // 状态色
    val GREEN = Color.rgb(0x2E, 0xC5, 0x6F)
    val YELLOW = Color.rgb(0xFF, 0xB3, 0x00)
    val RED = Color.rgb(0xE5, 0x39, 0x35)

    // 文本 / 徽章
    val TEXT = Color.rgb(0xEC, 0xF1, 0xFA)
    val SUBTEXT = Color.rgb(0x8A, 0x94, 0xA8)
    val BADGE = Color.rgb(0x35, 0x43, 0x66)

    fun dp(value: Int, density: Float): Int = (value * density).roundToInt()

    fun rounded(color: Int, radiusDp: Int, density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp, density).toFloat()
            setColor(color)
        }

    /** 带描边的圆角。 */
    fun strokeRounded(color: Int, radiusDp: Int, strokeDp: Int, strokeColor: Int, density: Float): GradientDrawable =
        rounded(color, radiusDp, density).apply {
            setStroke(dp(strokeDp, density), strokeColor)
        }

    fun pill(color: Int, density: Float): GradientDrawable = rounded(color, 20, density)

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** 环形描边(用于连接按钮外圈)。 */
    fun ring(strokeDp: Int, color: Int, density: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(strokeDp, density), color)
    }

    /** 线性渐变圆角背景。 */
    fun gradient(colors: IntArray, radiusDp: Int, density: Float): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, colors
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp, density).toFloat()
    }

    /** 线性渐变圆(连接按钮)。 */
    fun gradientOval(colors: IntArray): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, colors
    ).apply {
        shape = GradientDrawable.OVAL
    }

    fun ripple(background: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(30, 255, 255, 255)), background, null)
}