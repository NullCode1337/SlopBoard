/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * iOS-style notification badge: a white circle with a dark count, overlapping the tile's
 * top-right corner.
 */
object GlassBadge {
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = 0x24000000
    }

    @JvmStatic
    fun draw(canvas: Canvas, iconBounds: Rect, count: Int, scale: Float) {
        if (scale <= 0.01f || iconBounds.isEmpty) return
        val radius = iconBounds.width() * 0.175f * scale
        if (radius < 1f) return
        val cx = iconBounds.right - radius * 0.45f
        val cy = iconBounds.top + radius * 0.55f

        circlePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius, circlePaint)
        strokePaint.strokeWidth = max(1f, radius * 0.06f)
        canvas.drawCircle(cx, cy, radius - strokePaint.strokeWidth / 2f, strokePaint)

        val label = when {
            count <= 0 -> return
            count > 99 -> "99+"
            else -> count.toString()
        }
        textPaint.color = 0xFF1B1B20.toInt()
        textPaint.textSize = radius * if (label.length > 1) 1.02f else 1.30f
        val metrics = textPaint.fontMetrics
        val baseline = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, cx, baseline, textPaint)
    }

    /** Smallest sensible size, used by callers that need to reserve space. */
    @JvmStatic
    fun sizeFor(iconWidth: Int): Int = max(8, min(iconWidth, (iconWidth * 0.35f).toInt()))
}
