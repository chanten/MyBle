package com.example.myble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myble.ui.theme.MyBleTheme
import io.ktor.client.engine.cio.CIO
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val client = HttpClient(CIO) {
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d(TAG, "ktor $message")
                }
            }
            level = LogLevel.ALL
        }
    }

    private val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    private val leScanCallback = object : ScanCallback() {
        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device

            Log.d(TAG, "Device found: ${device.name ?: "Unnamed device"} - ${result.scanRecord}")
            if (!scannedDevices.contains(device)) {
                scannedDevices.add(device)
                Log.d(TAG, "Device found: ${device.name ?: "Unnamed device"} - ${device.address}")
            }
        }
    }

    // Bluetooth 활성화 요청 결과 처리
    private val enableBluetoothRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Bluetooth 활성화 성공
            Log.d(TAG, "Bluetooth enabled")
        } else {
            // Bluetooth 활성화 실패
            Log.d(TAG, "Failed to enable Bluetooth")
        }
    }

    // 권한 확인 및 요청 함수
    private fun checkAndRequestPermissions() {
        val notGrantedPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (notGrantedPermissions.isNotEmpty()) {
            multiplePermissionRequestForBle.launch(notGrantedPermissions)
        } else {
            Log.d(TAG, "All required permissions already granted")
            // 모든 권한이 이미 있는 경우 필요한 작업 수행 (예: 스캔 시작)
        }
    }

    // 복수 권한 요청 결과 처리
    private val multiplePermissionRequestForBle = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, isGranted) ->
            if (isGranted) {
                Log.d(TAG, "$permission granted")
                // 권한 획득 후 필요한 작업 수행 (예: 스캔 시작)
            } else {
                Log.d(TAG, "$permission denied")
                // 사용자에게 권한 필요성 알림 및 설정 화면으로 유도
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate")

        // 필요한 권한 확인 및 요청
        checkAndRequestPermissions()

        enableEdgeToEdge()
        setContent {
            MyBleTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var scanning by remember { mutableStateOf(false) }

                    Scaffold(
                        modifier = Modifier.fillMaxSize()
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(16.dp))

                            // Bluetooth 활성화 버튼
                            Button(onClick = {
                                if (!isBluetoothEnabled) {
                                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                    enableBluetoothRequest.launch(enableBtIntent)
                                } else {
                                    Log.d(TAG, "Bluetooth is already enabled")
                                }
                            }) {
                                Text("Enable Bluetooth")
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // BLE 스캔 시작/중지 버튼
                            Button(onClick = {
                                if (isBluetoothEnabled) {
                                    scanning = !scanning
                                    if (scanning) {
                                        scannedDevices.clear()
                                        startBleScan()
                                    } else {
                                        stopBleScan()
                                    }
                                } else {
                                    Log.d(TAG, "Bluetooth is not enabled")
                                }
                            }) {
                                Text(if (scanning) "Stop BLE Scan" else "Start BLE Scan")
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // 스캔된 장치 목록 표시
                            DeviceList(devices = scannedDevices)
                        }
                    }
                }
            }
        }

        val url =
            "https://api.smartthings.com/catalogs/api/v3/easysetup/discoverydata?mnId=0AFD&setupId=451&osType=android"

        lifecycleScope.launch {
            try {
                val response: HttpResponse = client.get(url)
                val body = response.bodyAsText()
                Log.d(TAG, "response $body")
            } catch (e: Exception) {
                Log.e(TAG, "response $e")
            }
        }
    }


    @Composable
    fun DeviceList(devices: List<BluetoothDevice>) = LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(16.dp)
    ) {
        items(devices) { device ->
            Text(text = "${device.name ?: "Unnamed device"} - ${device.address}")
        }
    }


    // BLE 스캔 시작
    private fun startBleScan() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "startBleScan: Permission BLUETOOTH_SCAN denied")
            return
        }

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "startBleScan: Permission BLUETOOTH_CONNECT denied")
            return
        }
        val tagServiceUuid = "fd59"
        val uuidPostFixString = "-0000-1000-8000-00805F9B34FB"
        val tagServiceParcelUuid: ParcelUuid =   ParcelUuid.fromString(tagServiceUuid + uuidPostFixString)
        val advVersionMask = 48 // 0010 0000 + 0001 0000
        val advVersionData = advVersionMask
        val advVersionDataMask = advVersionMask
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val scanFilter = android.bluetooth.le.ScanFilter.Builder()
            .build()
            /*.setServiceUuid(tagServiceParcelUuid)
            //.setServiceData(tagServiceParcelUuid, byteArrayOf(0x01.toByte(), 48.toByte()))
            .setServiceData(tagServiceParcelUuid,
                byteArrayOf(0x01.toByte(), advVersionData.toByte()),
                byteArrayOf(0xff.toByte(), advVersionDataMask.toByte()))
        .build()*/

        bluetoothAdapter?.bluetoothLeScanner?.startScan(listOf(scanFilter),scanSettings, leScanCallback)
        //bluetoothAdapter?.bluetoothLeScanner?.startScan(leScanCallback)
        Log.d(TAG, "BLE scan started")
    }

    // BLE 스캔 중지
    private fun stopBleScan() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "stopBleScan: Permission denied")
            return
        }
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        Log.d(TAG, "BLE scan stopped")
    }

    private var scannedDevices = mutableStateListOf<BluetoothDevice>()
    // 필요한 권한 목록
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyBleTheme {
        Greeting("Android")
    }
}