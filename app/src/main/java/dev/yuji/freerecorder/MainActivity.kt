package dev.yuji.freerecorder

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var timerView: TextView
    private lateinit var recordButton: Button
    private lateinit var recordingsList: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderState()
            handler.postDelayed(this, 1_000)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderState()
            loadRecordings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(RecordingService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        handler.post(ticker)
        loadRecordings()
    }

    override fun onPause() {
        unregisterReceiver(stateReceiver)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_RECORD_AUDIO -> {
                val permissionResults = permissions.zip(grantResults.toTypedArray()).toMap()
                val microphoneGranted =
                    permissionResults[Manifest.permission.RECORD_AUDIO] == PackageManager.PERMISSION_GRANTED
                if (microphoneGranted) {
                    RecordingService.start(this)
                } else {
                    Toast.makeText(this, "録音にはマイク権限が必要です", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 247, 249))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "Free Recorder"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(29, 33, 38))
        }
        root.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "会議音声をm4aで保存"
            textSize = 15f
            setTextColor(Color.rgb(92, 99, 110))
            setPadding(0, dp(4), 0, dp(18))
        }
        root.addView(subtitle, matchWrap())

        statusView = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(statusView, matchWrap())

        timerView = TextView(this).apply {
            textSize = 54f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.rgb(29, 33, 38))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        root.addView(timerView, matchWrap())

        recordButton = Button(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(56)
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (RecordingService.isRecording) {
                    RecordingService.stop(this@MainActivity)
                } else {
                    startRecordingWithPermissions()
                }
            }
        }
        root.addView(recordButton, matchWrap())

        val settingsButton = Button(this).apply {
            text = "アプリ設定を開く"
            setTextColor(Color.rgb(46, 125, 91))
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
        }
        root.addView(settingsButton, matchWrap(topMargin = dp(8)))

        val openRecordingsButton = Button(this).apply {
            text = "音声ファイルの場所を開く"
            setTextColor(Color.rgb(46, 125, 91))
            setOnClickListener {
                openRecordingsLocation()
            }
        }
        root.addView(openRecordingsButton, matchWrap(topMargin = dp(8)))

        val listTitle = TextView(this).apply {
            text = "録音ファイル"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(29, 33, 38))
            setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(listTitle, matchWrap())

        val scroll = ScrollView(this).apply {
            isFillViewport = false
        }
        recordingsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(recordingsList)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        renderState()
    }

    private fun startRecordingWithPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermissionIfNeeded()
            RecordingService.start(this)
        } else {
            requestPermissions(requiredPermissions().toTypedArray(), REQUEST_RECORD_AUDIO)
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    private fun renderState() {
        val recording = RecordingService.isRecording
        statusView.text = if (recording) {
            "録音中 ${RecordingService.currentFileName.orEmpty()}"
        } else {
            "待機中"
        }
        statusView.setTextColor(if (recording) Color.WHITE else Color.rgb(46, 125, 91))
        statusView.background = rounded(if (recording) Color.rgb(190, 47, 47) else Color.rgb(224, 239, 232), dp(8))

        val elapsed = if (recording) {
            max(0L, (System.currentTimeMillis() - RecordingService.startedAtMillis) / 1_000)
        } else {
            0L
        }
        timerView.text = formatElapsed(elapsed)

        recordButton.text = if (recording) "録音を停止" else "録音を開始"
        recordButton.background = rounded(if (recording) Color.rgb(190, 47, 47) else Color.rgb(46, 125, 91), dp(8))
    }

    private fun loadRecordings() {
        recordingsList.removeAllViews()
        val items = queryRecordings()
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "録音はまだありません"
                textSize = 15f
                setTextColor(Color.rgb(92, 99, 110))
                setPadding(0, dp(12), 0, dp(12))
            }
            recordingsList.addView(empty, matchWrap())
            return
        }

        items.forEach { item ->
            recordingsList.addView(recordingRow(item), matchWrap(bottomMargin = dp(8)))
        }
    }

    private fun queryRecordings(): List<RecordingItem> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.SIZE
            )
            val selection = "${MediaStore.Audio.Media.RELATIVE_PATH}=?"
            val selectionArgs = arrayOf("${Environment.DIRECTORY_MUSIC}/Meeting Recorder/")
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                return generateSequence {
                    if (cursor.moveToNext()) {
                        RecordingItem(
                            name = cursor.getString(nameIndex),
                            dateAddedSeconds = cursor.getLong(dateIndex),
                            sizeBytes = cursor.getLong(sizeIndex)
                        )
                    } else {
                        null
                    }
                }.take(50).toList()
            }
            return emptyList()
        }

        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Meeting Recorder")
        return directory.listFiles { file -> file.extension.equals("m4a", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(50)
            ?.map {
                RecordingItem(
                    name = it.name,
                    dateAddedSeconds = it.lastModified() / 1_000,
                    sizeBytes = it.length()
                )
            }
            .orEmpty()
    }

    private fun recordingRow(item: RecordingItem): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, dp(8))

            addView(TextView(context).apply {
                text = item.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(29, 33, 38))
            })

            addView(TextView(context).apply {
                text = "${formatDate(item.dateAddedSeconds)}  ${formatSize(item.sizeBytes)}"
                textSize = 13f
                setTextColor(Color.rgb(92, 99, 110))
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun formatElapsed(seconds: Long): String {
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val secs = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
    }

    private fun formatDate(dateAddedSeconds: Long): String {
        return DateFormat.format("yyyy/MM/dd HH:mm", dateAddedSeconds * 1_000).toString()
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun matchWrap(topMargin: Int = 0, bottomMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }
    }

    private fun openRecordingsLocation() {
        val documentId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "primary:${Environment.DIRECTORY_MUSIC}/Meeting Recorder"
        } else {
            "primary:Android/data/$packageName/files/${Environment.DIRECTORY_MUSIC}/Meeting Recorder"
        }
        val directoryUri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_PROVIDER_AUTHORITY,
            documentId
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(directoryUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showRecordingsLocationFallback()
        } catch (_: SecurityException) {
            showRecordingsLocationFallback()
        }
    }

    private fun showRecordingsLocationFallback() {
        val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Music > Meeting Recorder"
        } else {
            "Android > data > $packageName > files > Music > Meeting Recorder"
        }
        Toast.makeText(this, "ファイルアプリで $location を開いてください", Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class RecordingItem(
        val name: String,
        val dateAddedSeconds: Long,
        val sizeBytes: Long
    )

    companion object {
        private const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY = "com.android.externalstorage.documents"
        private const val REQUEST_RECORD_AUDIO = 10
        private const val REQUEST_POST_NOTIFICATIONS = 11
    }
}
