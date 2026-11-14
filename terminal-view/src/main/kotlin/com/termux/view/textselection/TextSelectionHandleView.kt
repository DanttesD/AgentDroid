package com.termux.view.textselection

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import com.termux.view.R
import com.termux.view.TerminalView

@SuppressLint("ViewConstructor")
class TextSelectionHandleView(
    private val terminalView: TerminalView,
    cursorController: CursorController,
    initialOrientation: Int
) : View(terminalView.getContext()) {
    private var mHandle: PopupWindow? = null
    private val mCursorController: CursorController = cursorController

    private val mHandleLeftDrawable: Drawable? = getContext().getDrawable(R.drawable.text_select_handle_left_material)
    private val mHandleRightDrawable: Drawable? = getContext().getDrawable(R.drawable.text_select_handle_right_material)
    private var mHandleDrawable: Drawable? = null

    private var mIsDragging = false

    private val mTempCoords = IntArray(2)
    private var mTempRect: Rect? = null

    private var mPointX = 0
    private var mPointY = 0
    private var mTouchToWindowOffsetX = 0.0f
    private var mTouchToWindowOffsetY = 0.0f
    private var mHotspotX = 0.0f
    private var mHotspotY = 0.0f
    private var mTouchOffsetY = 0.0f
    private var mLastParentX = 0
    private var mLastParentY = 0

    private var mHandleHeight = 0
    private var mHandleWidth = 0

    private val mInitialOrientation: Int = initialOrientation
    private var mOrientation = 0

    private var mLastTime = 0L

    // The constructor body of the Java original runs after every field above is initialised.
    init {
        setOrientation(mInitialOrientation)
    }

    private fun initHandle() {
        mHandle = PopupWindow(
            terminalView.getContext(), null,
            android.R.attr.textSelectHandleWindowStyle
        )
        val handle = mHandle!!
        handle.setSplitTouchEnabled(true)
        handle.setClippingEnabled(false)
        handle.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL)
        handle.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT)
        handle.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
        handle.setBackgroundDrawable(null)
        handle.setAnimationStyle(0)
        handle.setEnterTransition(null)
        handle.setExitTransition(null)
        handle.setContentView(this)
    }

    fun setOrientation(orientation: Int) {
        mOrientation = orientation
        var handleWidth = 0
        when (orientation) {
            LEFT -> {
                mHandleDrawable = mHandleLeftDrawable
                handleWidth = mHandleDrawable!!.getIntrinsicWidth()
                mHotspotX = (handleWidth * 3) / 4.0f
            }

            RIGHT -> {
                mHandleDrawable = mHandleRightDrawable
                handleWidth = mHandleDrawable!!.getIntrinsicWidth()
                mHotspotX = handleWidth / 4.0f
            }
        }

        mHandleHeight = mHandleDrawable!!.getIntrinsicHeight()

        mHandleWidth = handleWidth
        mTouchOffsetY = -mHandleHeight * 0.3f
        mHotspotY = 0.0f
        invalidate()
    }

    fun show() {
        if (!isPositionVisible()) {
            hide()
            return
        }

        // We remove handle from its parent first otherwise the following exception may be thrown
        // java.lang.IllegalStateException: The specified child already has a parent. You must call removeView() on the child's parent first.
        removeFromParent()

        initHandle() // init the handle
        invalidate() // invalidate to make sure onDraw is called

        val coords = mTempCoords
        terminalView.getLocationInWindow(coords)
        coords[0] += mPointX
        coords[1] += mPointY

        if (mHandle != null)
            mHandle!!.showAtLocation(terminalView, 0, coords[0], coords[1])
    }

    fun hide() {
        mIsDragging = false

        if (mHandle != null) {
            mHandle!!.dismiss()

            // We remove handle from its parent, otherwise it may still be shown in some cases even after the dismiss call
            removeFromParent()
            mHandle = null  // garbage collect the handle
        }
        invalidate()
    }

    fun removeFromParent() {
        if (!isParentNull()) {
            (this.getParent() as ViewGroup).removeView(this)
        }
    }

    fun positionAtCursor(cx: Int, cy: Int, forceOrientationCheck: Boolean) {
        val x = terminalView.getPointX(cx)
        val y = terminalView.getPointY(cy + 1)
        moveTo(x, y, forceOrientationCheck)
    }

    private fun moveTo(x: Int, y: Int, forceOrientationCheck: Boolean) {
        val oldHotspotX = mHotspotX
        checkChangedOrientation(x, forceOrientationCheck)
        mPointX = (x - (if (isShowing()) oldHotspotX else mHotspotX)).toInt()
        mPointY = y

        if (isPositionVisible()) {
            var coords: IntArray? = null

            if (isShowing()) {
                coords = mTempCoords
                terminalView.getLocationInWindow(coords)
                val x1 = coords[0] + mPointX
                val y1 = coords[1] + mPointY
                if (mHandle != null)
                    mHandle!!.update(x1, y1, getWidth(), getHeight())
            } else {
                show()
            }

            if (mIsDragging) {
                if (coords == null) {
                    coords = mTempCoords
                    terminalView.getLocationInWindow(coords)
                }
                if (coords[0] != mLastParentX || coords[1] != mLastParentY) {
                    mTouchToWindowOffsetX += coords[0] - mLastParentX
                    mTouchToWindowOffsetY += coords[1] - mLastParentY
                    mLastParentX = coords[0]
                    mLastParentY = coords[1]
                }
            }
        } else {
            hide()
        }
    }

    fun changeOrientation(orientation: Int) {
        if (mOrientation != orientation) {
            setOrientation(orientation)
        }
    }

    private fun checkChangedOrientation(posX: Int, force: Boolean) {
        if (!mIsDragging && !force) {
            return
        }
        val millis = SystemClock.currentThreadTimeMillis()
        if (millis - mLastTime < 50 && !force) {
            return
        }
        mLastTime = millis

        val hostView = terminalView
        val left = hostView.getLeft()
        val right = hostView.getWidth()
        val top = hostView.getTop()
        val bottom = hostView.getHeight()

        if (mTempRect == null) {
            mTempRect = Rect()
        }
        val clip = mTempRect!!
        clip.left = left + terminalView.getPaddingLeft()
        clip.top = top + terminalView.getPaddingTop()
        clip.right = right - terminalView.getPaddingRight()
        clip.bottom = bottom - terminalView.getPaddingBottom()

        val parent = hostView.getParent()
        if (parent == null || !parent.getChildVisibleRect(hostView, clip, null)) {
            return
        }

        if (posX - mHandleWidth < clip.left) {
            changeOrientation(RIGHT)
        } else if (posX + mHandleWidth > clip.right) {
            changeOrientation(LEFT)
        } else {
            changeOrientation(mInitialOrientation)
        }
    }

    private fun isPositionVisible(): Boolean {
        // Always show a dragging handle.
        if (mIsDragging) {
            return true
        }

        val hostView = terminalView
        val left = 0
        val right = hostView.getWidth()
        val top = 0
        val bottom = hostView.getHeight()

        if (mTempRect == null) {
            mTempRect = Rect()
        }
        val clip = mTempRect!!
        clip.left = left + terminalView.getPaddingLeft()
        clip.top = top + terminalView.getPaddingTop()
        clip.right = right - terminalView.getPaddingRight()
        clip.bottom = bottom - terminalView.getPaddingBottom()

        val parent = hostView.getParent()
        if (parent == null || !parent.getChildVisibleRect(hostView, clip, null)) {
            return false
        }

        val coords = mTempCoords
        hostView.getLocationInWindow(coords)
        val posX = coords[0] + mPointX + mHotspotX.toInt()
        val posY = coords[1] + mPointY + mHotspotY.toInt()

        return posX >= clip.left && posX <= clip.right &&
            posY >= clip.top && posY <= clip.bottom
    }

    public override fun onDraw(c: Canvas) {
        val width = mHandleDrawable!!.getIntrinsicWidth()
        val height = mHandleDrawable!!.getIntrinsicHeight()
        mHandleDrawable!!.setBounds(0, 0, width, height)
        mHandleDrawable!!.draw(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        terminalView.updateFloatingToolbarVisibility(event)
        when (event.getActionMasked()) {
            MotionEvent.ACTION_DOWN -> {
                val rawX = event.getRawX()
                val rawY = event.getRawY()
                mTouchToWindowOffsetX = rawX - mPointX
                mTouchToWindowOffsetY = rawY - mPointY
                val coords = mTempCoords
                terminalView.getLocationInWindow(coords)
                mLastParentX = coords[0]
                mLastParentY = coords[1]
                mIsDragging = true
            }

            MotionEvent.ACTION_MOVE -> {
                val rawX = event.getRawX()
                val rawY = event.getRawY()

                val newPosX = rawX - mTouchToWindowOffsetX + mHotspotX
                val newPosY = rawY - mTouchToWindowOffsetY + mHotspotY + mTouchOffsetY

                mCursorController.updatePosition(this, Math.round(newPosX), Math.round(newPosY))
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> mIsDragging = false
        }
        return true
    }

    public override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            mHandleDrawable!!.getIntrinsicWidth(),
            mHandleDrawable!!.getIntrinsicHeight()
        )
    }

    fun getHandleHeight(): Int {
        return mHandleHeight
    }

    fun getHandleWidth(): Int {
        return mHandleWidth
    }

    fun isShowing(): Boolean {
        return if (mHandle != null)
            mHandle!!.isShowing()
        else
            false
    }

    fun isParentNull(): Boolean {
        return this.getParent() == null
    }

    fun isDragging(): Boolean {
        return mIsDragging
    }

    companion object {
        const val LEFT = 0
        const val RIGHT = 2
    }
}
