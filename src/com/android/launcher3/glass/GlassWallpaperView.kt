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

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Draws the rasterised system wallpaper behind the launcher content.
 *
 * Launcher3 normally relies on `android:windowShowWallpaper`, which puts the wallpaper in a
 * separate window that no view can sample. For liquid glass we need a real bitmap in this window,
 * so this view paints the same wallpaper (center-cropped) as the first child of LauncherRootView.
 */
class GlassWallpaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var topScrim: Shader? = null
    private var bottomScrim: Shader? = null

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        gradientPaint.shader = LinearGradient(
            0f, 0f, 0f, resources.displayMetrics.heightPixels.toFloat(),
            0xFF0B1020.toInt(), 0xFF1B1033.toInt(), Shader.TileMode.CLAMP,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GlassBackdrop.ensureLoaded(context)
        GlassBackdrop.register(this)
    }

    override fun onDetachedFromWindow() {
        GlassBackdrop.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = h * 0.12f
        val bottom = h * 0.88f
        topScrim = LinearGradient(
            0f, 0f, 0f, top,
            0x5A000000, 0x00000000, Shader.TileMode.CLAMP,
        )
        bottomScrim = LinearGradient(
            0f, bottom, 0f, h.toFloat(),
            0x00000000, 0x5A000000, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (GlassBackdrop.sharp == null) {
            GlassBackdrop.ensureLoaded(context)
        }
        if (GlassBackdrop.sharp == null) {
            canvas.drawPaint(gradientPaint)
        } else {
            GlassBackdrop.drawWallpaper(canvas, fallbackPaint)
        }
        val top = topScrim
        if (top != null) {
            scrimPaint.shader = top
            canvas.drawRect(0f, 0f, width.toFloat(), height * 0.12f, scrimPaint)
        }
        val bottom = bottomScrim
        if (bottom != null) {
            scrimPaint.shader = bottom
            canvas.drawRect(0f, height * 0.88f, width.toFloat(), height.toFloat(), scrimPaint)
        }
    }
}
