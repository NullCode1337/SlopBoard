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

import android.view.View
import com.android.launcher3.PagedView

/**
 * The glass tiles are recorded into a CellLayout's display list together with the page's screen
 * position. PagedView moves pages by scrolling without re-laying them out, so the visible pages
 * must be invalidated whenever the workspace scrolls, otherwise the wallpaper sampled by the
 * lens is offset by the scroll delta.
 */
object GlassPageInvalidator {
    @JvmStatic
    fun invalidateVisiblePages(pagedView: PagedView<*>) {
        val scrollX = pagedView.scrollX
        val width = pagedView.width
        if (width <= 0) return
        for (i in 0 until pagedView.pageCount) {
            val page: View = pagedView.getPageAt(i)
            val left = page.left - scrollX + page.translationX.toInt()
            val right = left + page.width
            if (right > 0 && left < width) {
                page.invalidate()
            }
        }
    }

    @JvmStatic
    fun invalidateAll(pagedView: PagedView<*>) {
        for (i in 0 until pagedView.pageCount) {
            pagedView.getPageAt(i).invalidate()
        }
    }
}
