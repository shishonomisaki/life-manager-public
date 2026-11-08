package com.shishonomisaki.lifemanager.speech

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 手機內建語音轉文字（zh-TW）。有網路用線上辨識，沒網路才要求離線辨識。
 * 必須在主執行緒呼叫。
 *
 * 錯誤 11（和系統語音服務的連線斷掉）多半發生在剛綁定服務、或剛關掉上一個辨識器就馬上開新的，
 * 所以遇到這類錯誤會等一下、換一個新的辨識器自動重試，不直接報錯。
 */
class SpeechInput(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onStateChange: (listening: Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var retries = 0
    private var triedOnline = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        retries = 0
        triedOnline = false
        listen(preferOffline = !online())
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        session++
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** 重用同一個辨識器；只有出錯後才換新的，避免一直重綁系統服務。 */
    private fun recognizer(): SpeechRecognizer =
        recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

    private fun listen(preferOffline: Boolean) {
        handler.removeCallbacksAndMessages(null)
        val r = recognizer()
        val mine = ++session
        var gotResult = false
        var lastPartial: String? = null
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onStateChange(true)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onStateChange(false)
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (session != mine) return
                first(partialResults)?.let { lastPartial = it; onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                if (session != mine) return
                gotResult = true
                onStateChange(false)
                first(results)?.let(onFinal)
            }

            override fun onError(error: Int) {
                // 舊的辨識流程、或已經拿到結果後才報的錯，不用理
                if (session != mine || gotResult) return
                onStateChange(false)
                // 已經聽到一部分就當作結果，不報錯
                lastPartial?.let { gotResult = true; onFinal(it); return }

                // 連線斷掉、服務忙碌、用戶端錯誤：關掉換新的辨識器，稍等後自動重試
                val connectionProblem = error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT
                if (connectionProblem && retries < MAX_RETRIES) {
                    retries++
                    recognizer?.destroy()
                    recognizer = null
                    handler.postDelayed({ if (session == mine) listen(preferOffline) }, RETRY_DELAY_MS * retries)
                    return
                }
                // 離線語音包不支援中文：改用線上
                val offlineProblem = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                    error == SpeechRecognizer.ERROR_SERVER
                if (preferOffline && offlineProblem && !triedOnline) {
                    triedOnline = true
                    listen(preferOffline = false)
                    return
                }
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "沒有聽清楚，再說一次"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "沒有網路，也沒有離線語音包"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麥克風權限"
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "語音服務沒有回應，請再按一次麥克風"
                        else -> "語音辨識失敗（$error）"
                    },
                )
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(intent)
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 400L
    }
}
