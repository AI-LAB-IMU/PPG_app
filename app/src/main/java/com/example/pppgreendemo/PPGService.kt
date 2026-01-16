package com.example.ppggreendemo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.samsung.android.service.health.tracking.*
import com.samsung.android.service.health.tracking.data.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import android.content.pm.PackageManager

class PPGService : Service() {
    private val TAG = "PPGService"

    private var healthTrackingService: HealthTrackingService? = null
    private var greenTracker: HealthTracker? = null
    private var irTracker: HealthTracker? = null
    private var redTracker: HealthTracker? = null
    private var ppgContinuousTracker: HealthTracker? = null

    private lateinit var fileStreamer: FileStreamer
    private var filesReady = false

    // ===== 상태 플래그 =====
    private var isStopping = false

    // ===== UI ticker (1Hz 초 표시) =====
    private val uiHandler = Handler(Looper.getMainLooper())
    private var chunkCount = 0
    private var elapsedSeconds = 0
    private val uiTicker = object : Runnable {
        override fun run() {
            if (isStopping) return
            elapsedSeconds++
            sendUpdateBroadcast()
            uiHandler.postDelayed(this, 1000)
        }
    }

    // ===== 12초 청크 타이머 =====
    private val chunkHandler = Handler(Looper.getMainLooper())
    private val chunkTicker = object : Runnable {
        override fun run() {
            if (isStopping) return
            chunkCount++
            elapsedSeconds = 0
            sendUpdateBroadcast()
            chunkHandler.postDelayed(this, 12_000)
        }
    }

    // 사람이 읽는 시각(초 단위) — 로컬 타임존
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    private fun toLocalString(millis: Long): String = timeFmt.format(Date(millis))

    // 25 Hz 기준 샘플 간격(밀리초)
    private val SAMPLE_PERIOD_MS = 40L

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()

        val perms = listOf(
            android.Manifest.permission.BODY_SENSORS,
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA",
            "android.permission.BODY_SENSORS_BACKGROUND"
        )
        val status = perms.joinToString { p -> "$p=${checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED}" }
        Log.i(TAG, "Body sensor perms: $status")
    }

    private fun sanitize(s: String): String = s.replace(Regex("[^0-9A-Za-z가-힣_\\-]"), "_")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ===== 우아한 종료 트리거 =====
        if (intent?.action == "ACTION_STOP_GRACEFULLY") {
            gracefulStop()
            return START_NOT_STICKY
        }

        val subjectNumber = sanitize(intent?.getStringExtra("subject_number") ?: "N00")
        val subjectName   = sanitize(intent?.getStringExtra("subject_name") ?: "Unknown")

        val baseDownloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        val ppgRoot = File(baseDownloads, "PPG").apply { if (!exists()) mkdirs() }

        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val sessionDirName = "PPG_${ts}_${subjectNumber}_${subjectName}"
        val sessionDir = File(ppgRoot, sessionDirName).apply { if (!exists()) mkdirs() }
        Log.i(TAG, "Session dir = ${sessionDir.absolutePath}")

        try {
            fileStreamer = FileStreamer(sessionDir, "")
            val base = "${subjectNumber}_${subjectName}"
            fileStreamer.addFile("ppg_green", "${base}_ppg_green.csv", "timestamp,value\n")
            fileStreamer.addFile("ppg_ir",   "${base}_ppg_ir.csv",   "timestamp,value\n")
            fileStreamer.addFile("ppg_red",  "${base}_ppg_red.csv",  "timestamp,value\n")
            filesReady = true
        } catch (e: IOException) {
            filesReady = false
            Log.e(TAG, "File creation error: ${e.message}")
        }

        initTrackers()
        uiHandler.post(uiTicker)
        chunkHandler.postDelayed(chunkTicker, 12_000)
        return START_STICKY
    }

    private fun initTrackers() {
        healthTrackingService = HealthTrackingService(object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d(TAG, "✅ Connected to HealthTrackingService")

                val cap = healthTrackingService?.trackingCapability
                Log.i(TAG, "Support types = ${cap?.supportHealthTrackerTypes}")

                val types = setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)
                ppgContinuousTracker = healthTrackingService?.getHealthTracker(
                    HealthTrackerType.PPG_CONTINUOUS, types
                )
                ppgContinuousTracker?.setEventListener(ppgContinuousListener)
                Log.i(TAG, "PPG_CONTINUOUS registered (GREEN/IR/RED @25Hz)")
            }
            override fun onConnectionEnded() { Log.d(TAG, "ℹ️ HealthTrackingService connection ended") }
            override fun onConnectionFailed(e: HealthTrackerException?) { Log.e(TAG, "❌ Connection failed: ${e?.message}") }
        }, this)

        healthTrackingService?.connectService()
    }

    // 안전한 getter
    @Suppress("UNCHECKED_CAST")
    private fun getAny(dp: DataPoint, key: ValueKey<*>): Any? {
        return try { dp.getValue(key as ValueKey<Any?>) } catch (_: Throwable) { null }
    }

    private fun getPpgArray(dp: DataPoint, vararg keys: ValueKey<*>): FloatArray? {
        for (k in keys) {
            val v = getAny(dp, k) ?: continue
            when (v) {
                is FloatArray -> if (v.isNotEmpty()) return v
                is DoubleArray -> if (v.isNotEmpty()) return FloatArray(v.size) { v[it].toFloat() }
                is IntArray -> if (v.isNotEmpty()) return FloatArray(v.size) { v[it].toFloat() }
                is List<*> -> {
                    if (v.isNotEmpty() && v.first() is Number) {
                        val nums = v.filterIsInstance<Number>()
                        if (nums.isNotEmpty()) return FloatArray(nums.size) { nums[it].toFloat() }
                    }
                }
                is Number -> return floatArrayOf(v.toFloat())
            }
        }
        return null
    }

    // ===== 배치(배열) → 개별 샘플 타임스탬프(40ms 간격, END-anchored)로 전개하여 기록 =====
    private fun appendBatchExpanded(sb: StringBuilder, endTs: Long, values: FloatArray) {
        val n = values.size
        for (i in 0 until n) {
            val sampleTs = endTs - (n - 1 - i) * SAMPLE_PERIOD_MS // END-anchored
            sb.append("${toLocalString(sampleTs)},${values[i]}\n")
        }
    }

    // -------------------- PPG 연속 리스너 (25 Hz) --------------------
    private val ppgContinuousListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(list: List<DataPoint>) {
            if (!filesReady || list.isEmpty() || isStopping) return

            val sbG = StringBuilder(); val sbI = StringBuilder(); val sbR = StringBuilder()

            for (dp in list) {
                val endTs = dp.timestamp

                getPpgArray(dp, ValueKey.PpgSet.PPG_GREEN)?.let { appendBatchExpanded(sbG, endTs, it) }
                getPpgArray(dp, ValueKey.PpgSet.PPG_IR   )?.let { appendBatchExpanded(sbI, endTs, it) }
                getPpgArray(dp, ValueKey.PpgSet.PPG_RED  )?.let { appendBatchExpanded(sbR, endTs, it) }
            }

            if (sbG.isNotEmpty()) fileStreamer.addRecord("ppg_green", sbG.toString())
            if (sbI.isNotEmpty()) fileStreamer.addRecord("ppg_ir",   sbI.toString())
            if (sbR.isNotEmpty()) fileStreamer.addRecord("ppg_red",  sbR.toString())

        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError?) { Log.e(TAG, "PPG Continuous Error: $error") }
    }

    private fun sendUpdateBroadcast() {
        val intent = Intent("PPG_UPDATE").apply {
            putExtra("chunk_count", chunkCount)
            putExtra("elapsed_time", elapsedSeconds)
        }
        sendBroadcast(intent)
    }

    private fun startForegroundWithNotification() {
        val channelId = "ppg_logger_channel"
        val channelName = "PPG Logger Background"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PPG 수집 중")
            .setContentText("센서를 기록 중입니다.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(1, notification)
    }

    // ===== 우아한 종료: 잔여 배치 드레인 후 종료 =====
    private fun gracefulStop() {
        if (isStopping) return
        isStopping = true

        // 주기 작업 중단
        uiHandler.removeCallbacksAndMessages(null)
        chunkHandler.removeCallbacksAndMessages(null)

        // 잠깐 대기하여 워치→폰 잔여 배치 도착을 기다림
        Handler(Looper.getMainLooper()).postDelayed({
            try { ppgContinuousTracker?.unsetEventListener() } catch (_: Throwable) {}
            try { greenTracker?.unsetEventListener() } catch (_: Throwable) {}
            try { irTracker?.unsetEventListener() } catch (_: Throwable) {}
            try { redTracker?.unsetEventListener() } catch (_: Throwable) {}
            try { healthTrackingService?.disconnectService() } catch (_: Throwable) {}

            if (this::fileStreamer.isInitialized) {
                try { fileStreamer.endFiles() } catch (_: Throwable) {}
            }

            stopForeground(true)
            stopSelf()
        }, 8000) // 8초 정도면 대부분 배치가 도착
    }

    override fun onDestroy() {
        super.onDestroy()
        // 혹시 직접 destroy되면 방어적으로 처리
        if (!isStopping) gracefulStop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}