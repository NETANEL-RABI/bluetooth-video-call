package com.bluetoothvideo

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class BtState { NONE, LISTENING, CONNECTING, CONNECTED }

class BluetoothService(private val listener: BluetoothListener) {

    interface BluetoothListener {
        fun onConnected(deviceName: String)
        fun onConnectionFailed()
        fun onDisconnected()
        fun onDataReceived(data: ByteArray, length: Int)
    }

    companion object {
        private const val TAG = "BluetoothService"
        private const val APP_NAME = "BluetoothVideoCall"
        val APP_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var serverThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    var btState: BtState = BtState.NONE
        private set

    fun startServer() {
        stopAll()
        serverThread = AcceptThread()
        serverThread?.start()
        btState = BtState.LISTENING
    }

    fun connectToDevice(device: BluetoothDevice) {
        stopAll()
        connectThread = ConnectThread(device)
        connectThread?.start()
        btState = BtState.CONNECTING
    }

    fun sendData(data: ByteArray) {
        if (btState != BtState.CONNECTED) return
        connectedThread?.write(data)
    }

    fun stopAll() {
        serverThread?.cancel()
        serverThread = null
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        btState = BtState.NONE
    }

    private fun connected(socket: BluetoothSocket, deviceName: String) {
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
        btState = BtState.CONNECTED
        listener.onConnected(deviceName)
    }

    private inner class AcceptThread : Thread() {
        private val serverSocket: BluetoothServerSocket? = try {
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
        } catch (e: IOException) { null }

        override fun run() {
            var socket: BluetoothSocket?
            while (true) {
                if (btState == BtState.CONNECTED) break
                socket = try {
                    serverSocket?.accept()
                } catch (e: IOException) { break }
                if (socket != null) {
                    if (btState == BtState.LISTENING || btState == BtState.CONNECTING) {
                        serverSocket?.close()
                        connected(socket, socket.remoteDevice.name ?: "Unknown")
                    } else {
                        try { socket.close() } catch (e: IOException) { }
                    }
                }
