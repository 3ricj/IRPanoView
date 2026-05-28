package com.vilos.irpanoview.util

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Debug session d879b5 — NDJSON to device logs/debug-d879b5.log (pull with uvc-debug.log). */
object AgentDebugLog {

    private const val SESSION_ID = "d879b5"
    private const val FILE_NAME = "debug-d879b5.log"

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun log(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String = "pre-fix",
    ) {
        val ctx = appContext ?: return
        val payload = JSONObject().apply {
            put("sessionId", SESSION_ID)
            put("runId", runId)
            put("hypothesisId", hypothesisId)
            put("location", location)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject(data))
        }
        val line = payload.toString()
        // #region agent log
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            File(dir, FILE_NAME).appendText("$line\n")
        }
        UvcDebugLogger.log(ctx, "agent-dbg", line)
        // #endregion
    }
}
