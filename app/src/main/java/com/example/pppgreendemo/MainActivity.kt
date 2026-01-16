package com.example.ppggreendemo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.example.ppggreendemo.databinding.ActivityMainBinding

class MainActivity : Activity() {
    private lateinit var mTextView: TextView
    private lateinit var mButMeasure: Button
    private lateinit var mButSelectNumber: Button
    private lateinit var mButSelectName: Button
    private var isServiceRunning = false

    // 선택 목록
    private val numberOptions = (1..20).map { it.toString() }.toTypedArray()
    private val nameOptions = arrayOf("지민", "정윤", "하정", "승연", "긍요", "지연","주연","해름","윤지","희랑","재현")

    private var selectedNumber: String? = null
    private var selectedName: String? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val chunk = intent?.getIntExtra("chunk_count", 0) ?: 0
            val time = intent?.getIntExtra("elapsed_time", 0) ?: 0
            Log.d("MainActivity", "📥 Broadcast received - chunk: $chunk, time: $time")
            mTextView.text = "청크 수: $chunk | 경과 초: ${time}s"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mTextView = binding.txtOutput
        mButMeasure = binding.butStart
        mButSelectNumber = binding.butSelectNumber
        mButSelectName = binding.butSelectName

        mButMeasure.setOnClickListener { toggleService() }
        mButSelectNumber.setOnClickListener { showNumberPicker() }
        mButSelectName.setOnClickListener { showNamePicker() }

        registerReceiver(updateReceiver, IntentFilter("PPG_UPDATE"), Context.RECEIVER_EXPORTED)

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.BODY_SENSORS)

        // [EDA] 삼성 연속 EDA에 필요한 추가 권한 (런타임 요청)
        if (ActivityCompat.checkSelfPermission(
                this,
                "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add("com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA")
        }

        if (permissions.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(updateReceiver)
    }

    private fun showNumberPicker() {
        AlertDialog.Builder(this)
            .setTitle("번호 선택")
            .setItems(numberOptions) { _, which ->
                selectedNumber = numberOptions[which]
                mButSelectNumber.text = "번호: ${selectedNumber}"
            }
            .show()
    }

    private fun showNamePicker() {
        AlertDialog.Builder(this)
            .setTitle("이름 선택")
            .setItems(nameOptions) { _, which ->
                selectedName = nameOptions[which]
                mButSelectName.text = "이름: ${selectedName}"
            }
            .show()
    }

    private fun toggleService() {
        val intent = Intent(this, PPGService::class.java)

        if (!isServiceRunning) {
            if (selectedNumber.isNullOrBlank() || selectedName.isNullOrBlank()) {
                Toast.makeText(this, "측정 전 번호와 이름을 선택해 주세요.", Toast.LENGTH_SHORT).show()
                return
            }
            // ▶ 선택값 전달
            intent.putExtra("subject_number", selectedNumber)
            intent.putExtra("subject_name", selectedName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            mButMeasure.text = "정지"
            isServiceRunning = true
        } else {
            stopService(intent)
            mButMeasure.text = "시작"
            isServiceRunning = false
        }
    }
}
