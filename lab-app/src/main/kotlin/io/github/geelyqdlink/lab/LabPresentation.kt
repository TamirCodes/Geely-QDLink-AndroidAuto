package io.github.geelyqdlink.lab

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View

class LabPresentation(context: Context, display: Display) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(PatternView(context, display.displayId))
    }

    private class PatternView(context: Context, private val logicalDisplayId: Int) : View(context) {
        private val started = SystemClock.elapsedRealtime()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var touchX = -1f
        private var touchY = -1f

        override fun onDraw(canvas: Canvas) {
            val elapsed = SystemClock.elapsedRealtime() - started
            canvas.drawColor(Color.rgb(13, 22, 31))
            paint.color = Color.rgb(0, 188, 212)
            canvas.drawRect(0f, 0f, width * ((elapsed % 2000L) / 2000f), 24f, paint)
            paint.color = Color.WHITE
            paint.textSize = minOf(width, height) / 10f
            canvas.drawText("GE13 LAB", width * 0.08f, height * 0.35f, paint)
            paint.textSize = minOf(width, height) / 24f
            canvas.drawText("Presentation / no MediaProjection", width * 0.08f, height * 0.48f, paint)
            canvas.drawText("frame=${elapsed / 33}", width * 0.08f, height * 0.60f, paint)
            if (touchX >= 0) {
                paint.color = Color.YELLOW
                canvas.drawCircle(touchX, touchY, 28f, paint)
            }
            postInvalidateDelayed(33)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            touchX = event.x
            touchY = event.y
            LabLog.event(
                "PRESENTATION_TOUCH",
                "display=$logicalDisplayId action=${event.actionMasked} x=${event.x.toInt()} y=${event.y.toInt()}",
            )
            invalidate()
            return true
        }
    }
}
