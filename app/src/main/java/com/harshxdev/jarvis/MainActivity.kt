package com.harshxdev.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity(), RecognitionListener {
    private lateinit var router: CommandRouter
    private lateinit var commandInput: EditText
    private lateinit var status: TextView
    private lateinit var listen: Button
    private var recognizer: SpeechRecognizer? = null
    private val beginManualSpeech = Runnable { beginSpeechRecognition() }

    private lateinit var wakeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = CommandRouter(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val spacing = (24 * resources.displayMetrics.density).toInt()
            setPadding(spacing, spacing, spacing, spacing)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(spacing + bars.left, spacing + bars.top, spacing + bars.right, spacing + bars.bottom)
                }
                insets
            }
            addView(TextView(this@MainActivity).apply {
                text = "JARVIS Mark 1"
                textSize = 30f
            })
            commandInput = EditText(this@MainActivity).apply {
                hint = "Try “open Spotify”"
                setSingleLine()
            }
            addView(commandInput)
            addView(Button(this@MainActivity).apply {
                text = "Run command"
                setOnClickListener { runCommand(commandInput.text.toString()) }
            })
            listen = Button(this@MainActivity).apply {
                text = "Speak a command"
                setOnClickListener { requestVoiceCommand() }
            }
            addView(listen)
            wakeButton = Button(this@MainActivity).apply {
                text = "Enable wake word"
                setOnClickListener { toggleWakeWord() }
            }
            addView(wakeButton)
            addView(Button(this@MainActivity).apply {
                text = "Stop wake word"
                setOnClickListener {
                    stopService(Intent(this@MainActivity, WakeWordService::class.java))
                    status.text = "Wake word off."
                }
            })
            status = TextView(this@MainActivity).apply {
                text = "Ready"
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(status)
            addView(Button(this@MainActivity).apply {
                text = "Media control access"
                setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            })
        }
        setContentView(content)
    }

    private fun runCommand(command: String) {
        status.text = router.execute(command)
    }

    private fun requestVoiceCommand() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION)
            return
        }
        startListening()
    }

    private fun toggleWakeWord() {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            status.text = "On-device speech recognition is unavailable on this phone."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), WAKE_MICROPHONE_PERMISSION)
            return
        }
        requestWakeNotification()
    }

    private fun requestWakeNotification() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), WAKE_NOTIFICATION_PERMISSION)
            return
        }
        startWakeWord()
    }

    private fun startWakeWord() {
        startForegroundService(Intent(this, WakeWordService::class.java))
        status.text = "Starting wake-word listening. Say “Jarvis”, then wait for the beep."
    }

    private fun startListening() {
        listen.isEnabled = false
        wakeButton.isEnabled = false
        status.text = "Starting speech recognition…"
        val releasedWakeService = stopService(Intent(this, WakeWordService::class.java))
        if (releasedWakeService) status.postDelayed(beginManualSpeech, 350)
        else beginSpeechRecognition()
    }

    private fun beginSpeechRecognition() {
        if (recognizer == null && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        }
        val speech = recognizer
        if (speech == null) {
            listen.isEnabled = true
            wakeButton.isEnabled = true
            status.text = "On-device speech recognition is unavailable. You can type a command."
            return
        }
        speech.setRecognitionListener(this)
        try {
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            })
        } catch (_: RuntimeException) {
            onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WAKE_MICROPHONE_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestWakeNotification()
            else status.text = "Microphone access is needed for the wake word."
        }
        if (requestCode == WAKE_NOTIFICATION_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWakeWord()
            else status.text = "Allow notifications to see and stop wake-word listening."
        }
        if (requestCode == MICROPHONE_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
            else status.text = "Microphone access is needed for voice commands."
        }
    }

    override fun onResults(results: Bundle) {
        listen.isEnabled = true
        wakeButton.isEnabled = true
        val command = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (command.isNullOrBlank()) {
            status.text = "I didn’t catch that. Try again."
            return
        }
        commandInput.setText(command)
        runCommand(command)
    }

    override fun onError(error: Int) {
        listen.isEnabled = true
        wakeButton.isEnabled = true
        status.text = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn’t catch that. Try again."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "On-device speech recognition is unavailable for this language."
            SpeechRecognizer.ERROR_AUDIO -> "Android couldn’t start microphone capture. Try again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Android speech recognition is busy. Try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Android blocked microphone access."
            else -> "Voice recognition couldn’t finish. Try again or type a command."
        }
    }

    override fun onResume() {
        super.onResume()
        WakeWordService.activityVisible = true
        WakeWordService.statusListener = { status.text = it }
    }

    override fun onPause() {
        WakeWordService.activityVisible = false
        WakeWordService.statusListener = null
        status.removeCallbacks(beginManualSpeech)
        recognizer?.destroy()
        recognizer = null
        listen.isEnabled = true
        wakeButton.isEnabled = true
        super.onPause()
    }

    override fun onReadyForSpeech(params: Bundle) {
        status.text = "Listening now. Say your command."
    }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray) = Unit
    override fun onEndOfSpeech() { status.text = "Recognizing…" }
    override fun onPartialResults(partialResults: Bundle) = Unit
    override fun onEvent(eventType: Int, params: Bundle) = Unit

    private companion object {
        const val MICROPHONE_PERMISSION = 1
        const val WAKE_MICROPHONE_PERMISSION = 2
        const val WAKE_NOTIFICATION_PERMISSION = 3
    }
}
