package com.harshxdev.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.widget.Toast
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.Executors

class WakeWordService : Service(), RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var active = false
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeout = Runnable { finishCommand("Command timed out. Say “Jarvis” again.") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification("Starting wake-word detection…"))
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            finishService("On-device speech recognition is unavailable.")
            return START_NOT_STICKY
        }
        active = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:WakeWord").apply { acquire() }
        armWakeWord("Say “Jarvis”, wait for the beep, then speak.")
        return START_NOT_STICKY
    }

    private fun armWakeWord(message: String) {
        if (!active) return
        updateNotification(message)
        worker.execute {
            try {
                if (detectWakeWord()) main.post {
                    if (active) {
                        showWakeDetected()
                        listenForCommand()
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Wake-word detection stopped", error)
                main.post { if (active) finishService("Wake-word microphone or model failed.") }
            } catch (error: LinkageError) {
                Log.e(TAG, "Wake-word native library failed", error)
                main.post { if (active) finishService("Wake-word engine unavailable on this device.") }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun detectWakeWord(): Boolean {
        val spotter = KeywordSpotter(assets, KeywordSpotterConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "wake-model/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "wake-model/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    joiner = "wake-model/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                ),
                tokens = "wake-model/tokens.txt",
                numThreads = 1,
            ),
            keywordsFile = "jarvis-keywords.txt",
        ))
        try {
            val stream = spotter.createStream()
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                check(minBuffer > 0)
                val audio = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 3200)
                )
                try {
                    if (!active) return false
                    check(audio.state == AudioRecord.STATE_INITIALIZED)
                    audio.startRecording()
                    check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    Log.i(TAG, "Wake-word microphone recording")
                    main.post {
                        if (active) statusListener?.invoke("Wake word armed. Say “Jarvis”.")
                    }
                    val pcm = ShortArray(800)
                    while (active) {
                        val count = audio.read(pcm, 0, pcm.size)
                        check(count >= 0) { "AudioRecord read failed: $count" }
                        if (count == 0) continue
                        stream.acceptWaveform(FloatArray(count) { pcm[it] / 32768f }, SAMPLE_RATE)
                        while (spotter.isReady(stream)) {
                            spotter.decode(stream)
                            if (spotter.getResult(stream).keyword.isNotEmpty()) return true
                        }
                    }
                    return false
                } finally {
                    audio.release()
                }
            } finally {
                stream.release()
            }
        } finally {
            spotter.release()
        }
    }

    private fun showWakeDetected() {
        Log.i(TAG, "Wake word detected")
        val message = "Jarvis heard you. Get ready to speak."
        updateNotification(message)
        if (activityVisible) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 65, 110), -1))
        }
    }

    private fun listenForCommand() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { speech ->
                speech.setRecognitionListener(this)
                speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                })
            }
            main.postDelayed(timeout, 12_000)
            updateNotification("Jarvis heard you. Starting speech recognition…")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not start speech recognition", error)
            finishCommand("Couldn’t start speech recognition. Say “Jarvis” again.")
        }
    }

    override fun onReadyForSpeech(params: Bundle) {
        if (!active || recognizer == null) return
        updateNotification("Listening now. Say your command.")
        Log.i(TAG, "Speech recognizer ready")
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            main.postDelayed({ tone.release() }, 200)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not play the ready beep", error)
        }
    }

    override fun onResults(results: Bundle) {
        if (!active || recognizer == null) return
        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        val message = when {
            text.isNullOrBlank() -> "I didn’t catch that."
            else -> CommandRouter(this).execute(text, canOpenApps = activityVisible)
        }
        Log.i(TAG, "Voice command finished")
        finishCommand(message)
    }

    override fun onError(error: Int) {
        if (active && recognizer != null) {
            Log.w(TAG, "Speech recognizer error: $error")
            val reason = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Android blocked microphone access."
                SpeechRecognizer.ERROR_AUDIO -> "Speech recognition couldn’t use the microphone."
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                    "On-device speech recognition is unavailable for this language."
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn’t catch that."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Android speech recognition is busy."
                else -> "Android speech recognition failed."
            }
            finishCommand("$reason Say “Jarvis” again.")
        }
    }

    private fun finishCommand(message: String) {
        main.removeCallbacks(timeout)
        val speech = recognizer
        recognizer = null
        speech?.destroy()
        if (active) {
            updateNotification(message)
            main.postDelayed({ armWakeWord(message) }, 300)
        }
    }

    private fun finishService(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        stopSelf()
    }

    private fun updateNotification(text: String) {
        statusListener?.invoke(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("JARVIS listening")
            .setContentText(text)
            .setContentIntent(open)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop listening", stop).build())
            .build()
    }

    override fun onDestroy() {
        active = false
        main.removeCallbacks(timeout)
        recognizer?.destroy()
        recognizer = null
        worker.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle) = Unit
    override fun onEvent(eventType: Int, params: Bundle) = Unit

    companion object {
        internal var activityVisible = false
        internal var statusListener: ((String) -> Unit)? = null
        private const val TAG = "JarvisWakeWord"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = "wake_word"
        private const val NOTIFICATION_ID = 1
        private const val STOP = "com.harshxdev.jarvis.STOP_WAKE_WORD"
    }
}
