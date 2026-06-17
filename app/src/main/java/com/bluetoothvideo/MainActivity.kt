package com.bluetoothvideo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUEST_PERMISSIONS = 1001
    private val REQUEST_ENABLE_BT = 1002

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var deviceListView: ListView
    private lateinit var scanButton: Button
    private lateinit var waitButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val deviceList = mutableListOf<BluetoothDevice>()
    private val deviceNames = mutableListOf<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!deviceList.contains(it)) {
                            deviceList.add(it)
                            val name = try {
                                it.name ?: it.address
                            } catch (e: Exception) {
                                it.address
                            }
                            deviceNames.add(name)
                            listAdapter.notifyDataSetChanged()
                            statusText.text = "Found: $name"
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    progressBar.visibility = View.GONE
                    scanButton.isEnabled = true
                    statusText.text = if (deviceList.isEmpty())
                        "No devices found. Make sure other device is visible!"
                    else
                        "Found ${deviceList.size} devices. Select one."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        try {
            initViews()
            setupBluetooth()
            registerBluetoothReceiver()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initViews() {
        deviceListView = findViewById(R.id.deviceListView)
        scanButton = findViewById(R.id.scanButton)
        waitButton = findViewById(R.id.waitButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        deviceListView.adapter = listAdapter

        deviceListView.setOnItemClickListener { _, _, position, _ ->
            val device = deviceList[position]
            startVideoCall(device = device, isServer = false)
        }

        scanButton.setOnClickListener { startScan() }
        waitButton.setOnClickListener { startVideoCall(device = null, isServer = true) }
    }

    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported!", Toast.LENGTH_LONG).show()
            return
        }

        requestAllPermissions()
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        permissions.add(Manifest.permission.CAMERA)
        permissions.add(Manifest.permission.RECORD_AUDIO)

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            enableBluetooth()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            enableBluetooth()
        }
    }

    private fun enableBluetooth() {
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }
    }

    private fun startScan() {
        val adapter = bluetoothAdapter ?: return

        deviceList.clear()
        deviceNames.clear()
        listAdapter.notifyDataSetChanged()

        if (adapter.isDiscovering) adapter.cancelDiscovery()

        // הפוך את המכשיר לגלוי
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        startActivity(discoverableIntent)

        val started = adapter.startDiscovery()
        if (started) {
            progressBar.visibility = View.VISIBLE
            scanButton.isEnabled = false
            statusText.text = "Scanning for devices..."
        } else {
            statusText.text = "Failed to start scan. Check permissions!"
            Toast.makeText(this, "Scan failed! Grant all permissions.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startVideoCall(device: BluetoothDevice?, isServer: Boolean) {
        bluetoothAdapter?.cancelDiscovery()
        val intent = Intent(this, VideoCallActivity::class.java).apply {
            putExtra("IS_SERVER", isServer)
            device?.let { putExtra("DEVICE_ADDRESS", it.address) }
        }
        startActivity(intent)
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(bluetoothReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) { }
        bluetoothAdapter?.cancelDiscovery()
    }
}
