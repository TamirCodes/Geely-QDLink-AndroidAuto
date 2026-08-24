package io.github.geelyqdlink.lab

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

object LabLog {
    private const val TAG = "QDLinkLab"
    private val lines = CopyOnWriteArrayList<String>()

    fun event(name: String, detail: String = "") {
        val line = "${SystemClock.elapsedRealtime()} $name${if (detail.isBlank()) "" else " $detail"}"
        lines += line
        while (lines.size > 300) lines.removeAt(0)
        Log.i(TAG, line)
    }

    fun snapshot(): String = lines.joinToString("\n")
}

