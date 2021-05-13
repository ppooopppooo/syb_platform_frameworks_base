/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.events

import android.animation.Animator
import android.annotation.UiThread
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

import com.android.internal.annotations.GuardedBy
import com.android.systemui.animation.Interpolators
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher
import com.android.systemui.statusbar.phone.StatusBarMarginUpdatedListener
import com.android.systemui.util.concurrency.DelayableExecutor

import java.lang.IllegalStateException
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Understands how to keep the persistent privacy dot in the corner of the screen in
 * ScreenDecorations, which does not rotate with the device.
 *
 * The basic principle here is that each dot will sit in a box that is equal to the margins of the
 * status bar (specifically the status_bar_contents view in PhoneStatusBarView). Each dot container
 * will have its gravity set towards the corner (i.e., top-right corner gets top|right gravity), and
 * the contained ImageView will be set to center_vertical and away from the corner horizontally. The
 * Views will match the status bar top padding and status bar height so that the dot can appear to
 * reside directly after the status bar system contents (basically after the battery).
 *
 * NOTE: any operation that modifies views directly must run on the provided executor, because
 * these views are owned by ScreenDecorations and it runs in its own thread
 */

@SysUISingleton
class PrivacyDotViewController @Inject constructor(
    @Main private val mainExecutor: Executor,
    private val stateController: StatusBarStateController,
    private val locationPublisher: StatusBarLocationPublisher,
    private val animationScheduler: SystemStatusAnimationScheduler
) {
    private var sbHeightPortrait = 0
    private var sbHeightLandscape = 0

    private lateinit var tl: View
    private lateinit var tr: View
    private lateinit var bl: View
    private lateinit var br: View

    // Only can be modified on @UiThread
    private var currentViewState: ViewState = ViewState()

    @GuardedBy("lock")
    private var nextViewState: ViewState = currentViewState.copy()
        set(value) {
            field = value
            scheduleUpdate()
        }
    private val lock = Object()
    private var cancelRunnable: Runnable? = null

    // Privacy dots are created in ScreenDecoration's UiThread, which is not the main thread
    private var uiExecutor: DelayableExecutor? = null

    private val marginListener: StatusBarMarginUpdatedListener =
            object : StatusBarMarginUpdatedListener {
        override fun onStatusBarMarginUpdated(marginLeft: Int, marginRight: Int) {
            setStatusBarMargins(marginLeft, marginRight)
        }
    }

    private val views: Sequence<View>
        get() = if (!this::tl.isInitialized) sequenceOf() else sequenceOf(tl, tr, br, bl)

    init {
        locationPublisher.addCallback(marginListener)

        stateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onExpandedChanged(isExpanded: Boolean) {
                updateStatusBarState()
            }

            override fun onStateChanged(newState: Int) {
                updateStatusBarState()
            }
        })
    }

    fun setUiExecutor(e: DelayableExecutor) {
        uiExecutor = e
    }

    fun setQsExpanded(expanded: Boolean) {
        dlog("setQsExpanded $expanded")
        synchronized(lock) {
            nextViewState = nextViewState.copy(qsExpanded = expanded)
        }
    }

    @UiThread
    fun setNewRotation(rot: Int) {
        dlog("updateRotation: $rot")

        synchronized(lock) {
            if (rot == nextViewState.rotation) {
                return
            }
        }

        // If we rotated, hide all dotes until the next state resolves
        setCornerVisibilities(View.INVISIBLE)

        val newCorner = selectDesignatedCorner(rot)
        val index = newCorner.cornerIndex()

        val h = when (rot) {
            0, 2 -> sbHeightPortrait
            1, 3 -> sbHeightLandscape
            else -> 0
        }
        synchronized(lock) {
            nextViewState = nextViewState.copy(
                    rotation = rot,
                    height = h,
                    designatedCorner = newCorner,
                    cornerIndex = index)
        }
    }

    @UiThread
    private fun hideDotView(dot: View, animate: Boolean) {
        dot.clearAnimation()
        if (animate) {
            dot.animate()
                    .setDuration(DURATION)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .alpha(0f)
                    .withEndAction { dot.visibility = View.INVISIBLE }
                    .start()
        } else {
            dot.visibility = View.INVISIBLE
        }
    }

    @UiThread
    private fun showDotView(dot: View, animate: Boolean) {
        dot.clearAnimation()
        if (animate) {
            dot.visibility = View.VISIBLE
            dot.alpha = 0f
            dot.animate()
                    .alpha(1f)
                    .setDuration(DURATION)
                    .setInterpolator(Interpolators.ALPHA_IN)
                    .start()
        } else {
            dot.visibility = View.VISIBLE
            dot.alpha = 1f
        }
    }

    @UiThread
    private fun updateHeights(rot: Int) {
        val height = when (rot) {
            0, 2 -> sbHeightPortrait
            1, 3 -> sbHeightLandscape
            else -> 0
        }

        views.forEach { it.layoutParams.height = height }
    }

    // Update the gravity and margins of the privacy views
    @UiThread
    private fun updateRotations(rotation: Int) {
        // To keep a view in the corner, its gravity is always the description of its current corner
        // Therefore, just figure out which view is in which corner. This turns out to be something
        // like (myCorner - rot) mod 4, where topLeft = 0, topRight = 1, etc. and portrait = 0, and
        // rotating the device counter-clockwise increments rotation by 1

        views.forEach { corner ->
            val rotatedCorner = rotatedCorner(cornerForView(corner), rotation)
            (corner.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = rotatedCorner.toGravity()
            }

            // Set the dot's view gravity to hug the status bar
            (corner.findViewById<View>(R.id.privacy_dot)
                    .layoutParams as FrameLayout.LayoutParams)
                        .gravity = rotatedCorner.innerGravity()
        }
    }

    @UiThread
    private fun updateCornerSizes(l: Int, r: Int, rotation: Int) {
        views.forEach { corner ->
            val rotatedCorner = rotatedCorner(cornerForView(corner), rotation)
            val w = widthForCorner(rotatedCorner, l, r)
            (corner.layoutParams as FrameLayout.LayoutParams).width = w
        }
    }

    // Designated view will be the one at statusbar's view.END
    @UiThread
    private fun selectDesignatedCorner(r: Int): View? {
        if (!this::tl.isInitialized) {
            return null
        }

        val isRtl = tl.isLayoutRtl

        return when (r) {
            0 -> if (isRtl) tl else tr
            1 -> if (isRtl) tr else br
            2 -> if (isRtl) br else bl
            3 -> if (isRtl) bl else tl
            else -> throw IllegalStateException("unknown rotation")
        }
    }

    // Track the current designated corner and maybe animate to a new rotation
    @UiThread
    private fun updateDesignatedCorner(newCorner: View?, shouldShowDot: Boolean) {
        if (shouldShowDot) {
            newCorner?.apply {
                clearAnimation()
                visibility = View.VISIBLE
                alpha = 0f
                animate()
                    .alpha(1.0f)
                    .setDuration(300)
                    .start()
            }
        }
    }

    @UiThread
    private fun setCornerVisibilities(vis: Int) {
        views.forEach { corner ->
            corner.visibility = vis
        }
    }

    private fun cornerForView(v: View): Int {
        return when (v) {
            tl -> TOP_LEFT
            tr -> TOP_RIGHT
            bl -> BOTTOM_LEFT
            br -> BOTTOM_RIGHT
            else -> throw IllegalArgumentException("not a corner view")
        }
    }

    private fun rotatedCorner(corner: Int, rotation: Int): Int {
        var modded = corner - rotation
        if (modded < 0) {
            modded += 4
        }

        return modded
    }

    private fun widthForCorner(corner: Int, left: Int, right: Int): Int {
        return when (corner) {
            TOP_LEFT, BOTTOM_LEFT -> left
            TOP_RIGHT, BOTTOM_RIGHT -> right
            else -> throw IllegalArgumentException("Unknown corner")
        }
    }

    fun initialize(topLeft: View, topRight: View, bottomLeft: View, bottomRight: View) {
        if (this::tl.isInitialized && this::tr.isInitialized &&
                this::bl.isInitialized && this::br.isInitialized) {
            if (tl == topLeft && tr == topRight && bl == bottomLeft && br == bottomRight) {
                return
            }
        }

        tl = topLeft
        tr = topRight
        bl = bottomLeft
        br = bottomRight

        val dc = selectDesignatedCorner(0)
        val index = dc.cornerIndex()

        mainExecutor.execute {
            animationScheduler.addCallback(systemStatusAnimationCallback)
        }

        synchronized(lock) {
            nextViewState = nextViewState.copy(designatedCorner = dc, cornerIndex = index)
        }
    }

    /**
     * Set the status bar height in portrait and landscape, in pixels. If they are the same you can
     * pass the same value twice
     */
    fun setStatusBarHeights(portrait: Int, landscape: Int) {
        sbHeightPortrait = portrait
        sbHeightLandscape = landscape
    }

    /**
     * The dot view containers will fill the margin in order to position the dots correctly
     *
     * @param left the space between the status bar contents and the left side of the screen
     * @param right space between the status bar contents and the right side of the screen
     */
    private fun setStatusBarMargins(left: Int, right: Int) {
        dlog("setStatusBarMargins l=$left r=$right")
        synchronized(lock) {
            nextViewState = nextViewState.copy(marginLeft = left, marginRight = right)
        }
    }

    private fun updateStatusBarState() {
        synchronized(lock) {
            nextViewState = nextViewState.copy(shadeExpanded = isShadeInQs())
        }
    }

    /**
     * If we are unlocked with an expanded shade, QS is showing. On keyguard, the shade is always
     * expanded so we use other signals from the panel view controller to know if QS is expanded
     */
    @GuardedBy("lock")
    private fun isShadeInQs(): Boolean {
        return (stateController.isExpanded && stateController.state == SHADE) ||
                (stateController.state == SHADE_LOCKED)
    }

    private fun scheduleUpdate() {
        dlog("scheduleUpdate: ")

        cancelRunnable?.run()
        cancelRunnable = uiExecutor?.executeDelayed({
            processNextViewState()
        }, 100)
    }

    @UiThread
    private fun processNextViewState() {
        dlog("processNextViewState: ")

        val newState: ViewState
        synchronized(lock) {
            newState = nextViewState.copy()
        }

        resolveState(newState)
    }

    @UiThread
    private fun resolveState(state: ViewState) {
        dlog("resolveState $state")
        if (state == currentViewState) {
            dlog("resolveState: skipping")
            return
        }

        if (state.rotation != currentViewState.rotation) {
            // A rotation has started, hide the views to avoid flicker
            updateRotations(state.rotation)
        }

        if (state.height != currentViewState.height) {
            updateHeights(state.rotation)
        }

        if (state.marginLeft != currentViewState.marginLeft ||
                state.marginRight != currentViewState.marginRight) {
            updateCornerSizes(state.marginLeft, state.marginRight, state.rotation)
        }

        if (state.designatedCorner != currentViewState.designatedCorner) {
            updateDesignatedCorner(state.designatedCorner, state.shouldShowDot())
        }

        if (state.needsLayout(currentViewState)) {
            views.forEach { it.requestLayout() }
        }

        val shouldShow = state.shouldShowDot()
        if (shouldShow != currentViewState.shouldShowDot()) {
            if (shouldShow && state.designatedCorner != null) {
                showDotView(state.designatedCorner, true)
            } else if (!shouldShow && state.designatedCorner != null) {
                hideDotView(state.designatedCorner, true)
            }
        }

        currentViewState = state
    }

    private val systemStatusAnimationCallback: SystemStatusAnimationCallback =
            object : SystemStatusAnimationCallback {
        override fun onSystemStatusAnimationTransitionToPersistentDot(): Animator? {
            synchronized(lock) {
                nextViewState = nextViewState.copy(systemPrivacyEventIsActive = true)
            }

            return null
        }

        override fun onHidePersistentDot(): Animator? {
            synchronized(lock) {
                nextViewState = nextViewState.copy(systemPrivacyEventIsActive = false)
            }

            return null
        }
    }

    private fun View?.cornerIndex(): Int {
        if (this != null) {
            return cornerForView(this)
        }
        return -1
    }
}

private fun dlog(s: String) {
    if (DEBUG) {
        Log.d(TAG, s)
    }
}

private fun vlog(s: String) {
    if (DEBUG_VERBOSE) {
        Log.d(TAG, s)
    }
}

const val TOP_LEFT = 0
const val TOP_RIGHT = 1
const val BOTTOM_RIGHT = 2
const val BOTTOM_LEFT = 3
private const val DURATION = 160L
private const val TAG = "PrivacyDotViewController"
private const val DEBUG = true
private const val DEBUG_VERBOSE = false

private fun Int.toGravity(): Int {
    return when (this) {
        TOP_LEFT -> Gravity.TOP or Gravity.LEFT
        TOP_RIGHT -> Gravity.TOP or Gravity.RIGHT
        BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.LEFT
        BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.RIGHT
        else -> throw IllegalArgumentException("Not a corner")
    }
}

private fun Int.innerGravity(): Int {
    return when (this) {
        TOP_LEFT -> Gravity.CENTER_VERTICAL or Gravity.RIGHT
        TOP_RIGHT -> Gravity.CENTER_VERTICAL or Gravity.LEFT
        BOTTOM_LEFT -> Gravity.CENTER_VERTICAL or Gravity.RIGHT
        BOTTOM_RIGHT -> Gravity.CENTER_VERTICAL or Gravity.LEFT
        else -> throw IllegalArgumentException("Not a corner")
    }
}

private data class ViewState(
    val systemPrivacyEventIsActive: Boolean = false,
    val shadeExpanded: Boolean = false,
    val qsExpanded: Boolean = false,

    val rotation: Int = 0,
    val height: Int = 0,
    val marginLeft: Int = 0,
    val marginRight: Int = 0,
    val cornerIndex: Int = -1,
    val designatedCorner: View? = null
) {
    fun shouldShowDot(): Boolean {
        return systemPrivacyEventIsActive && !shadeExpanded && !qsExpanded
    }

    fun needsLayout(other: ViewState): Boolean {
        return rotation != other.rotation ||
                marginRight != other.marginRight ||
                height != other.height
    }
}
