package com.example.voicebrainlive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class CallRecordingService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    
    companion object {
        const val TAG = "CallRecording"
        const val CHANNEL_ID = "call_recording_channel"
        const val NOTIFICATION_ID = 2001
        var onRecordingComplete: ((String) -> Unit)? = null
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForegroundWithNotification()
        startRecording()
        return START_NOT_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Recording phone calls"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Recording")
            .setContentText("Recording in progress...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun startRecording() {
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile("${cacheDir}/call_recording.m4a")
                prepare()
                start()
            }
            isRecording = true
            Log.d(TAG, "Recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}")
            stopSelf()
        }
    }
    
    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            
            val filePath = "${cacheDir}/call_recording.m4a"
            onRecordingComplete?.invoke(filePath)
            Log.d(TAG, "Recording saved: $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}")
        }
        stopSelf()
    }
    
    override fun onDestroy() {
        if (isRecording) stopRecording()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
