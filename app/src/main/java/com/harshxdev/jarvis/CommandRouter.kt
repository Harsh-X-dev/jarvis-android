package com.harshxdev.jarvis

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

class MediaAccessService : NotificationListenerService()

class CommandRouter(private val context: Context) {
    fun execute(input: String, canOpenApps: Boolean = true): String {
        val command = input.trim().replace(Regex("^jarvis[ ,]+", RegexOption.IGNORE_CASE), "")
        return when {
            command.startsWith("open ", ignoreCase = true) -> {
                if (canOpenApps) openApp(command.drop(5).trim())
                else "Open JARVIS to launch apps from a command."
            }
            command.lowercase() in mediaCommands -> controlMedia(command.lowercase())
            else -> "Try “open Spotify”, “pause”, or “next track”."
        }
    }

    private fun openApp(name: String): String {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = context.packageManager.queryIntentActivities(launcher, 0)
            .filter { it.loadLabel(context.packageManager).toString().equals(name, ignoreCase = true) }
            .distinctBy { it.activityInfo.packageName }
        if (matches.size > 1) return "More than one installed app is named $name."
        val match = matches.singleOrNull() ?: return "I couldn’t find an app named $name."
        val activity = match.activityInfo
        return try {
            val intent = Intent.makeMainActivity(ComponentName(activity.packageName, activity.name))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opening ${match.loadLabel(context.packageManager)}."
        } catch (_: ActivityNotFoundException) {
            "I couldn’t open $name."
        } catch (_: SecurityException) {
            "Android blocked access to $name."
        }
    }

    private fun controlMedia(command: String): String {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val sessions = try {
            manager.getActiveSessions(ComponentName(context, MediaAccessService::class.java))
        } catch (_: SecurityException) {
            return "Enable JARVIS under Notification access to control media."
        }
        val player = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
            ?: return "Open a media app and start playback first."
        val controls = player.transportControls
        when (command) {
            "play", "resume" -> controls.play()
            "pause" -> controls.pause()
            "next", "next track" -> controls.skipToNext()
            "previous", "previous track" -> controls.skipToPrevious()
        }
        return "Sent “$command” to the media player."
    }

    private companion object {
        val mediaCommands = setOf("play", "resume", "pause", "next", "next track", "previous", "previous track")
    }
}
