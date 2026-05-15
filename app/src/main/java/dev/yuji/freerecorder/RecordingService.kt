package dev.yuji.freerecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var segmentStartMillis = 0L
    private val silenceCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsed = System.currentTimeMillis() - segmentStartMillis
            // MAX_SEGMENT_DURATION に達したら強制ローテーション
            if (elapsed >= MAX_SEGMENT_DURATION_MILLIS) {
                rotateRecordingSegment()
                return
            }
            // MIN_SEGMENT_DURATION 経過後、無音ならローテーション
            val amplitude = recorder?.maxAmplitude ?: 0
            if (amplitude <= SILENCE_AMPLITUDE_THRESHOLD) {
                rotateRecordingSegment()
                return
            }
            handler.postDelayed(this, SILENCE_CHECK_INTERVAL_MILLIS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> startRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(silenceCheckRunnable)
        if (isRecording) {
            stopRecording()
        }
        super.onDestroy()
    }

    private fun startRecording() {
        if (isRecording) return

        startedAtMillis = System.currentTimeMillis()
        if (startNewSegment()) {
            isRecording = true
            scheduleSegmentRotation()
            sendStateChanged()
            return
        }

        cleanupFailedDestination()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Toast.makeText(this, "録音を開始できませんでした", Toast.LENGTH_LONG).show()
    }

    private fun startNewSegment(): Boolean {
        val fileName = buildFileName()
        currentFileName = fileName
        val notification = buildNotification("録音中: $fileName")
        if (isRecording) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return try {
            val destination = createDestination(fileName)
            outputUri = destination.uri
            outputFile = destination.file
            outputDescriptor = destination.descriptor

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(AUDIO_BIT_RATE)
                setAudioSamplingRate(44_100)
                destination.descriptor?.fileDescriptor?.let(::setOutputFile)
                    ?: setOutputFile(requireNotNull(destination.file).absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder
            true
        } catch (error: Exception) {
            cleanupFailedDestination()
            false
        }
    }

    private fun stopRecording() {
        handler.removeCallbacks(silenceCheckRunnable)
        finalizeCurrentSegment()

        isRecording = false
        startedAtMillis = 0L
        currentFileName = null
        outputUri = null
        outputFile = null
        sendStateChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun rotateRecordingSegment() {
        if (!isRecording) return

        val saved = finalizeCurrentSegment()
        outputUri = null
        outputFile = null

        if (saved && startNewSegment()) {
            scheduleSegmentRotation()
            sendStateChanged()
            return
        }

        isRecording = false
        startedAtMillis = 0L
        currentFileName = null
        sendStateChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Toast.makeText(this, "録音ファイルの自動分割に失敗しました", Toast.LENGTH_LONG).show()
    }

    private fun scheduleSegmentRotation() {
        handler.removeCallbacks(silenceCheckRunnable)
        segmentStartMillis = System.currentTimeMillis()
        // MIN_SEGMENT_DURATION 後から無音チェックを開始する
        handler.postDelayed(silenceCheckRunnable, MIN_SEGMENT_DURATION_MILLIS)
    }

    private fun finalizeCurrentSegment(): Boolean {
        val activeRecorder = recorder
        recorder = null
        var stoppedSuccessfully = false

        if (activeRecorder != null) {
            stoppedSuccessfully = runCatching { activeRecorder.stop() }.isSuccess
            runCatching { activeRecorder.release() }
        }

        outputDescriptor?.close()
        outputDescriptor = null

        if (!stoppedSuccessfully) {
            outputUri?.let { uri ->
                runCatching { contentResolver.delete(uri, null, null) }
            }
            outputFile?.delete()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputUri?.let { uri ->
                contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
        } else {
            outputFile?.let { file ->
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("audio/mp4"),
                    null
                )
            }
        }
        return stoppedSuccessfully
    }

    private fun createDestination(fileName: String): RecordingDestination {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Meeting Recorder")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)) {
                "MediaStoreに録音ファイルを作成できませんでした"
            }
            val descriptor = requireNotNull(contentResolver.openFileDescriptor(uri, "w")) {
                "録音ファイルを開けませんでした"
            }
            return RecordingDestination(uri = uri, descriptor = descriptor, file = null)
        }

        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Meeting Recorder")
        directory.mkdirs()
        val file = File(directory, fileName)
        return RecordingDestination(uri = null, descriptor = null, file = file)
    }

    private fun cleanupFailedDestination() {
        outputDescriptor?.close()
        outputDescriptor = null
        outputUri?.let { uri ->
            runCatching { contentResolver.delete(uri, null, null) }
        }
        outputFile?.delete()
        outputUri = null
        outputFile = null
        isRecording = false
        startedAtMillis = 0L
        currentFileName = null
        sendStateChanged()
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_24)
            .setContentTitle("Free Recorder")
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_mic_24, "停止", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "会議音声の録音状態を表示します"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun sendStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun buildFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "meeting_$timestamp.m4a"
    }

    private data class RecordingDestination(
        val uri: Uri?,
        val descriptor: ParcelFileDescriptor?,
        val file: File?
    )

    companion object {
        const val ACTION_START = "dev.yuji.freerecorder.START"
        const val ACTION_STOP = "dev.yuji.freerecorder.STOP"
        const val ACTION_STATE_CHANGED = "dev.yuji.freerecorder.STATE_CHANGED"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val AUDIO_BIT_RATE = 64_000
        /** 無音チェックを開始する最小録音時間（25分）*/
        private const val MIN_SEGMENT_DURATION_MILLIS = 25L * 60L * 1_000L
        /** 無音が検出できなかった場合の強制切り替え上限（35分）*/
        private const val MAX_SEGMENT_DURATION_MILLIS = 35L * 60L * 1_000L
        /** 無音とみなす振幅しきい値（0〜32767）*/
        private const val SILENCE_AMPLITUDE_THRESHOLD = 100
        /** 無音チェックの間隔（1秒）*/
        private const val SILENCE_CHECK_INTERVAL_MILLIS = 1_000L

        @Volatile
        var isRecording: Boolean = false
            private set

        @Volatile
        var startedAtMillis: Long = 0L
            private set

        @Volatile
        var currentFileName: String? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
