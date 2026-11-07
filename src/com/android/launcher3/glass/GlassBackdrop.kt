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

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.view.View
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The wallpaper rendered into screen-space bitmaps that [GlassSurface] can sample.
 *
 * Launcher3 shows the system wallpaper in a separate window, so a glass view cannot capture it
 * through the normal view hierarchy. This class rasterises the wallpaper once, center-cropped to
 * the display, and keeps a small blurred copy for the lens shader. It is intentionally
 * screen-space: bitmap pixel (x, y) is exactly screen pixel (x, y), which makes the shader
 * sampling trivial and avoids re-cropping on every frame.
 */
object GlassBackdrop {
    private const val TAG = "GlassBackdrop"

    @Volatile
    var sharp: Bitmap? = null
        private set

    @Volatile
    var soft: Bitmap? = null
        private set

    /** soft.width / sharp.width (used by the shader's blur texture matrix). */
    @Volatile
    var softScale: Float = 1f / 8f
        private set

    private var loadedWallpaperId = Int.MIN_VALUE
    private var loadedWidth = 0
    private var loadedHeight = 0
    private var loading = false

    private val listeners = CopyOnWriteArrayList<WeakReference<View>>()

    /**
     * Loads the wallpaper if it is not already resident for this wallpaper id / display size.
     * Safe to call from the UI thread: this is a one-shot operation per wallpaper change.
     */
    @JvmStatic
    fun ensureLoaded(context: Context) {
        val app = context.applicationContext
        val wm = WallpaperManager.getInstance(app)
        val id = try {
            wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (t: Throwable) {
            -1
        }
        val dm = app.resources.displayMetrics
        val windowManager = app.getSystemService(android.view.WindowManager::class.java)
        val bounds = windowManager?.currentWindowMetrics?.bounds
        val width = bounds?.width() ?: dm.widthPixels
        val height = bounds?.height() ?: dm.heightPixels
        synchronized(this) {
            if (sharp != null && id == loadedWallpaperId
                && width == loadedWidth && height == loadedHeight) {
                return
            }
            if (loading) return
            loading = true
        }
        try {
            load(app, wm, id, width, height)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to rasterise wallpaper", t)
        } finally {
            synchronized(this) { loading = false }
        }
    }

    private fun load(
        app: Context,
        wm: WallpaperManager,
        wallpaperId: Int,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF101018.toInt())

        val drawable = try {
            wm.drawable
        } catch (t: Throwable) {
            Log.w(TAG, "getDrawable failed", t)
            null
        }
        if (drawable != null) {
            val iw = drawable.intrinsicWidth
            val ih = drawable.intrinsicHeight
            if (iw > 0 && ih > 0) {
                val scale = max(width / iw.toFloat(), height / ih.toFloat())
                val dw = (iw * scale).roundToInt()
                val dh = (ih * scale).roundToInt()
                val left = (width - dw) / 2
                val top = (height - dh) / 2
                drawable.setBounds(left, top, left + dw, top + dh)
            } else {
                drawable.setBounds(0, 0, width, height)
            }
            drawable.draw(canvas)
        }

        val blurW = max(1, width / 8)
        val blurH = max(1, height / 8)
        val blurred = Bitmap.createScaledBitmap(bitmap, blurW, blurH, true)

        sharp = bitmap
        soft = blurred
        softScale = min(blurW / width.toFloat(), blurH / height.toFloat())
        loadedWallpaperId = wallpaperId
        loadedWidth = width
        loadedHeight = height
        notifyChanged()
    }

    /** Forces a reload, e.g. after returning from the wallpaper picker. */
    @JvmStatic
    fun refresh(context: Context) {
        synchronized(this) { loadedWallpaperId = Int.MIN_VALUE }
        ensureLoaded(context)
    }

    @JvmStatic
    fun register(view: View) {
        if (listeners.none { it.get() === view }) {
            listeners.add(WeakReference(view))
        }
    }

    @JvmStatic
    fun unregister(view: View) {
        listeners.removeAll { it.get() === view || it.get() == null }
    }

    private fun notifyChanged() {
        for (ref in listeners) {
            val view = ref.get() ?: continue
            view.post {
                view.invalidate()
                (view.parent as? View)?.invalidate()
            }
        }
    }

    /** Paints the static wallpaper. Used by [GlassWallpaperView]. */
    @JvmStatic
    fun drawWallpaper(canvas: Canvas, fallbackPaint: Paint) {
        val bitmap = sharp
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } else {
            canvas.drawPaint(fallbackPaint)
        }
    }
}
