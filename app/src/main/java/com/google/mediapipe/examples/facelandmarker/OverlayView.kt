package com.google.mediapipe.examples.facelandmarker

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    // Paint configurations
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 50f
        isAntiAlias = true
    }

    private val warningPaint = Paint().apply {
        color = Color.RED
        textSize = 80f
        isAntiAlias = true
        style = Paint.Style.FILL
        strokeWidth = 3f
    }

    private val landmarkPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 8f
        style = Paint.Style.FILL
    }

    private val statsBackgroundPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0) // Semi-transparent black
        style = Paint.Style.FILL
    }

    // Data variables
    private var earValue = 0f
    private var marValue = 0f
    private var isDrowsy = false
    private var landmarks: List<NormalizedLandmark> = emptyList()
    private var blinkCount = 0
    private var yawnCount = 0
    private var microsleepDuration = 0f
    private var yawnDuration = 0f
    private var alertText = ""

    fun setDrowsinessData(
        ear: Float,
        mar: Float,
        drowsy: Boolean,
        newLandmarks: List<NormalizedLandmark>,
        blinkCount: Int,
        yawnCount: Int,
        microsleepDuration: Float,
        yawnDuration: Float,
        alertText: String = ""
    ) {
        earValue = ear
        marValue = mar
        isDrowsy = drowsy
        landmarks = newLandmarks
        this.blinkCount = blinkCount
        this.yawnCount = yawnCount
        this.microsleepDuration = microsleepDuration
        this.yawnDuration = yawnDuration
        this.alertText = alertText
        invalidate()
    }

    fun clear() {
        earValue = 0f
        marValue = 0f
        isDrowsy = false
        landmarks = emptyList()
        blinkCount = 0
        yawnCount = 0
        microsleepDuration = 0f
        yawnDuration = 0f
        alertText = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw stats background
        canvas.drawRect(0f, 0f, 400f, 450f, statsBackgroundPaint)

        // Draw EAR/MAR values
        canvas.drawText("EAR: %.2f".format(earValue), 20f, 50f, textPaint)
        canvas.drawText("MAR: %.2f".format(marValue), 20f, 100f, textPaint)

        // Draw statistics
        canvas.drawText("Blinks: $blinkCount", 20f, 150f, textPaint)
        canvas.drawText("Microsleep: %.2fs".format(microsleepDuration), 20f, 200f, textPaint)
        canvas.drawText("Yawns: $yawnCount", 20f, 250f, textPaint)
        canvas.drawText("Yawn Dur: %.2fs".format(yawnDuration), 20f, 300f, textPaint)

        // Draw key landmarks (same as Python version)
        landmarks.getOrNull(144)?.let { drawLandmark(canvas, it) }   // Left eye
        landmarks.getOrNull(373)?.let { drawLandmark(canvas, it) }  // Right eye
        landmarks.getOrNull(0)?.let { drawLandmark(canvas, it) }    // Nose tip
        landmarks.getOrNull(61)?.let { drawLandmark(canvas, it) }   // Left mouth corner
        landmarks.getOrNull(291)?.let { drawLandmark(canvas, it) }  // Right mouth corner
        landmarks.getOrNull(13)?.let { drawLandmark(canvas, it) }   // Upper lip
        landmarks.getOrNull(14)?.let { drawLandmark(canvas, it) }   // Lower lip

        // Draw alert if present
        if (alertText.isNotEmpty()) {
            val alertY = height / 2f
            val alertX = width / 2f - warningPaint.measureText(alertText) / 2

            // Draw alert background
            val textWidth = warningPaint.measureText(alertText)
            canvas.drawRect(
                alertX - 20f,
                alertY - 70f,
                alertX + textWidth + 20f,
                alertY + 20f,
                statsBackgroundPaint
            )

            // Draw alert text
            canvas.drawText(alertText, alertX, alertY, warningPaint)
        }

        // Draw drowsiness warning (legacy support)
        if (isDrowsy && alertText.isEmpty()) {
            canvas.drawText("DROWSY!", width/2f - 150f, height/2f, warningPaint)
        }
    }

    private fun drawLandmark(canvas: Canvas, landmark: NormalizedLandmark) {
        canvas.drawCircle(
            landmark.x() * width,
            landmark.y() * height,
            10f,
            landmarkPaint
        )
    }
}