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

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import com.android.launcher3.Utilities
import kotlin.math.max
import kotlin.math.min

/**
 * Draws iOS 26 style liquid-glass shapes straight over the wallpaper bitmap.
 *
 * Unlike a full [com.example.liquidglass.LiquidGlassView] per icon, this samples the pre-rendered
 * wallpaper directly in one AGSL pass and needs no off-screen capture. That keeps 24 workspace
 * tiles + 4 dock tiles inside a single frame budget on API 33+ hardware.
 *
 * The shader is a reduced version of Liquid Glass 2.0's lens pipeline: SDF shape, inverse-power
 * bevel, inward refraction, per-channel dispersion and the two-lobe rim specular. The original
 * implementation and its MIT licence live in libs/liquidglass.
 */
object GlassSurface {
    private const val TAG = "GlassSurface"

    private const val LENS_AGSL = """
        uniform shader wallpaper;
        uniform shader blurred;
        uniform float4 shape;       // cx, cy, halfWidth, halfHeight
        uniform float  radius;
        uniform float  bevel;
        uniform float  refract;
        uniform float  falloff;
        uniform float  dispersion;
        uniform float  specStrength;
        uniform float2 lightDir;
        uniform float4 glassTint;
        uniform float  dimAmount;
        uniform float  blurMix;
        uniform float  satFactor;
        uniform float  opacity;

        float sdRound(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
        }

        float sdf(float2 coord) {
            return sdRound(coord - shape.xy, shape.zw, radius);
        }

        half4 main(float2 coord) {
            float d = sdf(coord);
            float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
            if (cov <= 0.004 || opacity <= 0.004) {
                return half4(0.0);
            }

            float2 n = float2(
                sdf(coord + float2(1.0, 0.0)) - sdf(coord - float2(1.0, 0.0)),
                sdf(coord + float2(0.0, 1.0)) - sdf(coord - float2(0.0, 1.0))
            );
            float nLen = length(n);
            n = nLen > 0.0001 ? n / nLen : float2(0.0, -1.0);

            float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
            float slope;
            if (falloff > 0.001) {
                float tail = pow(5.0, -falloff);
                slope = (pow(1.0 + 4.0 * t, -falloff) - tail) / (1.0 - tail);
            } else {
                slope = (1.0 - t) * (1.0 - t);
            }

            // Inward sampling, matching iOS: the rim is a compressed mirror of the content
            // underneath the glass.
            float2 offset = -n * (slope * refract);
            float2 cR = coord + offset * (1.0 - dispersion * slope);
            float2 cG = coord + offset;
            float2 cB = coord + offset * (1.0 + dispersion * slope);

            float3 col;
            col.r = mix(float(wallpaper.eval(cR).r), float(blurred.eval(cR).r), blurMix);
            col.g = mix(float(wallpaper.eval(cG).g), float(blurred.eval(cG).g), blurMix);
            col.b = mix(float(wallpaper.eval(cB).b), float(blurred.eval(cB).b), blurMix);

            float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
            col = clamp(mix(float3(lum), col, satFactor), float3(0.0), float3(1.0));
            col = mix(col, glassTint.rgb, glassTint.a);
            col = col * (1.0 - dimAmount);

            float facing = dot(n, -lightDir);
            float lobeF = pow(max(facing, 0.0), 4.5);
            float lobeB = pow(max(-facing, 0.0), 4.5);
            float bandW = clamp(bevel * 0.3, 2.0, 6.0);
            float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
            float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5) * cov;
            float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;
            float spec = (hair * 0.72 * (lobeF + lobeB) + glow * 0.10 * lobeF)
                         * specStrength;
            col += float3(spec);

            col = clamp(col, float3(0.0), float3(1.0));
            return half4(half3(col * cov * opacity), half(cov * opacity));
        }
    """

    /** Visual parameters of one glass plate. */
    data class Style(
        val cornerFraction: Float = 0.224f,
        val cornerRadiusPx: Float = -1f,
        val bevelFraction: Float = 0.16f,
        val refractFraction: Float = 0.30f,
        val dispersion: Float = 0.10f,
        val blur: Float = 0.30f,
        val tint: Int = 0x0DFFFFFF,
        val dim: Float = 0.02f,
        val spec: Float = 1.0f,
        val saturation: Float = 1.18f,
        val opacity: Float = 1f,
        val stroke: Int = 0x24FFFFFF,
    ) {
        companion object {
            @JvmField
            val ICON_LIGHT = Style()

            @JvmField
            val ICON_DARK = Style(
                tint = 0x42000000,
                dim = 0.10f,
                spec = 0.92f,
                stroke = 0x30FFFFFF,
            )

            @JvmField
            val FOLDER = Style(
                cornerFraction = 0.22f,
                bevelFraction = 0.10f,
                refractFraction = 0.34f,
                tint = 0x12FFFFFF,
                dim = 0.02f,
                blur = 0.38f,
            )

            @JvmField
            val TASK = Style(
                cornerFraction = 0.16f,
                tint = 0x10FFFFFF,
                dim = 0.03f,
                blur = 0.62f,
                spec = 0.9f,
                stroke = 0x30FFFFFF,
            )
        }
    }

    private var shader: RuntimeShader? = null
    private var sharpShader: BitmapShader? = null
    private var softShader: BitmapShader? = null
    private var shaderBroken = false
    private var backdropBitmap: android.graphics.Bitmap? = null
    private var softBitmap: android.graphics.Bitmap? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val location = IntArray(2)
    private val rectF = RectF()

    private var frameValid = false
    private var frameHost: View? = null
    private var frameX = 0f
    private var frameY = 0f

    @JvmStatic
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun ensureShader(context: android.content.Context): Boolean {
        if (shaderBroken) return false
        if (!isSupported()) return false
        if (GlassBackdrop.sharp == null) {
            GlassBackdrop.ensureLoaded(context)
        }
        val sharp = GlassBackdrop.sharp ?: return false
        val soft = GlassBackdrop.soft ?: return false
        if (shader != null && sharp === backdropBitmap && soft === softBitmap) {
            return true
        }
        return try {
            if (shader == null) {
                shader = RuntimeShader(LENS_AGSL)
            }
            sharpShader = BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            softShader = BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader!!.setInputShader("wallpaper", sharpShader!!)
            shader!!.setInputShader("blurred", softShader!!)
            backdropBitmap = sharp
            softBitmap = soft
            true
        } catch (t: Throwable) {
            Log.e(TAG, "AGSL lens shader failed to compile; using fallback", t)
            shaderBroken = true
            shader = null
            false
        }
    }

    /**
     * Starts a drawing batch for [host]. Call [end] when done. The host's screen position is
     * baked into the wallpaper shader matrices, so the caller must invalidate the host whenever
     * it moves.
     */
    @JvmStatic
    fun begin(host: View): Boolean {
        frameHost = host
        frameValid = false
        if (!ensureShader(host.context)) return false
        val sharp = backdropBitmap ?: return false
        val soft = softBitmap ?: return false
        host.getLocationOnScreen(location)
        frameX = location[0].toFloat()
        frameY = location[1].toFloat()
        // Screen-space bitmaps: local (0,0) must resolve to the host's screen position.
        matrix.setTranslate(frameX, frameY)
        sharpShader?.setLocalMatrix(matrix)
        val s = GlassBackdrop.softScale
        matrix.setScale(s, s)
        matrix.postTranslate(frameX * s, frameY * s)
        softShader?.setLocalMatrix(matrix)
        paint.shader = shader
        paint.alpha = 255
        frameValid = true
        return true
    }

    @JvmStatic
    fun end() {
        frameHost = null
        frameValid = false
    }

    @JvmStatic
    fun draw(canvas: Canvas, host: View, rect: RectF, style: Style) {
        if (rect.isEmpty) return
        if (!frameValid || frameHost !== host) {
            if (!begin(host)) {
                drawFallback(canvas, rect, style)
                return
            }
        }
        drawShape(canvas, rect, style)
    }

    private fun drawShape(canvas: Canvas, rect: RectF, style: Style) {
        val sh = shader
        if (sh == null) {
            drawFallback(canvas, rect, style)
            return
        }
        val w = rect.width()
        val h = rect.height()
        if (w <= 0f || h <= 0f) return
        val shortSide = min(w, h)
        val radius = if (style.cornerRadiusPx >= 0f) {
            min(style.cornerRadiusPx, shortSide / 2f)
        } else {
            shortSide * style.cornerFraction
        }
        val bevelFraction = style.bevelFraction
        val refractFraction = style.refractFraction
        val bevel = max(2f, shortSide * bevelFraction)
        val refract = max(2f, shortSide * refractFraction)

        sh.setFloatUniform("shape", rect.centerX(), rect.centerY(), w / 2f, h / 2f)
        sh.setFloatUniform("radius", radius)
        sh.setFloatUniform("bevel", bevel)
        sh.setFloatUniform("refract", refract)
        sh.setFloatUniform("falloff", 2.0f)
        sh.setFloatUniform("dispersion", style.dispersion)
        sh.setFloatUniform("specStrength", style.spec)
        sh.setFloatUniform("lightDir", -0.7071f, -0.7071f)
        sh.setFloatUniform(
            "glassTint",
            Color.red(style.tint) / 255f,
            Color.green(style.tint) / 255f,
            Color.blue(style.tint) / 255f,
            Color.alpha(style.tint) / 255f,
        )
        sh.setFloatUniform("dimAmount", style.dim)
        sh.setFloatUniform("blurMix", style.blur)
        sh.setFloatUniform("satFactor", style.saturation)
        sh.setFloatUniform("opacity", style.opacity)

        val save = canvas.save()
        canvas.clipRect(rect.left - 2f, rect.top - 2f, rect.right + 2f, rect.bottom + 2f)
        canvas.drawRect(rect, paint)
        canvas.restoreToCount(save)

        if (Color.alpha(style.stroke) > 0) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = max(1f, Utilities.dpToPx(0.8f).toFloat())
            strokePaint.color = style.stroke
            rectF.set(rect)
            rectF.inset(0.5f, 0.5f)
            canvas.drawRoundRect(rectF, radius, radius, strokePaint)
        }
    }

    private fun drawFallback(canvas: Canvas, rect: RectF, style: Style) {
        val shortSide = min(rect.width(), rect.height())
        val radius = if (style.cornerRadiusPx >= 0f) {
            style.cornerRadiusPx
        } else {
            shortSide * style.cornerFraction
        }
        fallbackPaint.style = Paint.Style.FILL
        fallbackPaint.color = if (Color.alpha(style.tint) > 0) {
            style.tint
        } else {
            0x1AFFFFFF
        }
        canvas.drawRoundRect(rect, radius, radius, fallbackPaint)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = max(1f, Utilities.dpToPx(0.8f).toFloat())
        strokePaint.color = style.stroke
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    /** A square icon tile rectangle centered in [view]. */
    @JvmStatic
    fun iconRect(view: View, iconSizePx: Int): RectF {
        val side = min(
            iconSizePx.toFloat(),
            min(view.width.toFloat(), view.height.toFloat()),
        )
        val left = (view.width - side) / 2f
        val top = (view.height - side) / 2f
        return RectF(left, top, left + side, top + side)
    }

    /**
     * Picks the per-icon tone. Most tiles are clear; a deterministic subset uses the darker
     * iOS tone (Phone, Facebook, Settings and other "heavy" apps read that way in the reference).
     */
    @JvmStatic
    fun styleForPackage(packageName: CharSequence?): Style {
        val pkg = packageName?.toString()?.lowercase() ?: ""
        if (pkg.isEmpty()) return Style.ICON_LIGHT
        val dark = pkg in DARK_PACKAGES ||
            (pkg.contains("dialer")) ||
            (pkg.contains("facebook")) ||
            (pkg.contains("recorder")) ||
            (pkg == "com.android.settings") ||
            (pkg.contains("google.android.apps.maps")) ||
            (pkg.hashCode() and 7) == 0
        return if (dark) Style.ICON_DARK else Style.ICON_LIGHT
    }

    private val DARK_PACKAGES = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.settings",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.google.android.apps.maps",
        "com.google.android.apps.recorder",
    )
}
