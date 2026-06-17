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

    private lateinit var bluetoothAdapter: BluetoothAdapter
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
                            val name = if (hasBluetoothPermission()) it.name ?: it.address else it.address
                            deviceNames.add(name)
                            listAdapter.notifyDataSetChanged()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    progressBar.visibility = View.GONE
                    scanButton.isEnabled = true
                    statusText.text = if (deviceList.isEmpty())
                        "No devices found."
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
            statusText.text = "Bluetooth not supported"
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        } else {
            requestPermissionsIfNeeded()
        }
    }

    private fun startScan() {
        if (!hasBluetoothPermission()) {
            requestPermissionsIfNeeded()
            return
        }
        deviceList.clear()
        deviceNames.clear()
        listAdapter.notifyDataSetChanged()
        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        startActivity(discoverableIntent)
        bluetoothAdapter.startDiscovery()
        progressBar.visibility = View.VISIBLE
        scanButton.isEnabled = false
        statusText.text = "Scanning..."
    }

    private fun startVideoCall(device: BluetoothDevice?, isServer: Boolean) {
        bluetoothAdapter.cancelDiscovery()
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
        }
        registerReceiver(bluetoothReceiver, filter)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!hasPermission(Manifest.permission.CAMERA))
            permissions.add(Manifest.permission.CAMERA)
        if (!hasPermission(Manifest.permission.RECORD_AUDIO))
            permissions.add(Manifest.permission.RECORD_AUDIO)
        if (permissions.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        else true

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) { }
        if (::bluetoothAdapter.isInitialized && bluetoothAdapter.isDiscovering)
            bluetoothAdapter.cancelDiscovery()
    }
}
