package com.bluetoothvideo

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class VideoCallActivity : AppCompatActivity(), BluetoothService.BluetoothListener {

    private val SAMPLE_RATE = 8000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val VIDEO_MARKER = byteArrayOf(0x56, 0x49, 0x44)
    private val AUDIO_MARKER = byteArrayOf(0x41, 0x55, 0x44)

    private lateinit var localVideoView: TextureView
    private lateinit var remoteVideoView: ImageView
    private lateinit var statusText: TextView
    private lateinit var endCallButton: Button
    private lateinit var muteButton: Button

    private lateinit var bluetoothService: BluetoothService
    private var isServer = false
    private var deviceAddress: String? = null

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private var isStreamingVideo = false

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isStreamingAudio = false
    private var isMuted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_call)
        isServer = intent.getBooleanExtra("IS_SERVER", false)
        deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        initViews()
        bluetoothService = BluetoothService(this)
        startConnection()
    }

    private fun initViews() {
        localVideoView = findViewById(R.id.localVideoView)
        remoteVideoView = findViewById(R.id.remoteVideoView)
        statusText = findViewById(R.id.statusText)
        endCallButton = findViewById(R.id.endCallButton)
        muteButton = findViewById(R.id.muteButton)
        endCallButton.setOnClickListener { endCall() }
        muteButton.setOnClickListener {
            isMuted = !isMuted
            muteButton.text = if (isMuted) "Unmute" else "Mute"
        }
        statusText.text = if (isServer) "Waiting..." else "Connecting..."
    }

    private fun startConnection() {
        if (isServer) {
            bluetoothService.startServer()
        } else {
            deviceAddress?.let {
                val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val device: BluetoothDevice = bm.adapter.getRemoteDevice(it)
                bluetoothService.connectToDevice(device)
            }
        }
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread {
            statusText.text = "Connected: $deviceName"
            Toast.makeText(this, "Connected! $deviceName", Toast.LENGTH_SHORT).show()
        }
        startBackgroundThread()
        startCamera()
        startAudioStreaming()
    }

    override fun onConnectionFailed() {
        runOnUiThread {
            statusText.text = "Connection failed"
            endCallButton.text = "Back"
        }
    }

    override fun onDisconnected() {
        runOnUiThread { statusText.text = "Disconnected" }
        stopStreaming()
    }

    override fun onDataReceived(data: ByteArray, length: Int) {
        if (length < 3) return
        val marker = data.take(3).toByteArray()
        val payload = data.drop(3).toByteArray()
        when {
            marker.contentEquals(VIDEO_MARKER) -> handleIncomingVideo(payload)
            marker.contentEquals(AUDIO_MARKER) -> handleIncomingAudio(payload)
        }
    }

    private fun startCamera() {
        if (localVideoView.isAvailable) {
            openCamera()
        } else {
            localVideoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openCamera() }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }
    }

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList[1]
        imageReader = ImageReader.newInstance(
            VideoProcessor.FRAME_WIDTH,
            VideoProcessor.FRAME_HEIGHT,
            ImageFormat.YUV_420_888, 2
        )
        imageReader?.setOnImageAvailableListener({ reader ->
            val image: Image? = reader.acquireLatestImage()
            image?.let {
                if (isStreamingVideo && bluetoothService.btState == BtState.CONNECTED) {
                    val jpeg = VideoProcessor.imageToJpegBytes(it)
                    bluetoothService.sendData(VIDEO_MARKER + jpeg)
                }
                it.close()
            }
        }, backgroundHandler)
        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) return
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                cameraDevice = camera
                createCameraSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                camera.close()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                cameraOpenCloseLock.release()
                camera.close()
            }
        }, backgroundHandler)
    }

    private fun createCameraSession() {
        val texture = localVideoView.surfaceTexture ?: return
        texture.setDefaultBufferSize(VideoProcessor.FRAME_WIDTH, VideoProcessor.FRAME_HEIGHT)
        val previewSurface = Surface(texture)
        val imageSurface = imageReader!!.surface
        val captureRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            addTarget(imageSurface)
        }
        cameraDevice!!.createCaptureSession(
            listOf(previewSurface, imageSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                    isStreamingVideo = true
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            backgroundHandler
        )
    }

    private fun handleIncomingVideo(jpegData: ByteArray) {
        val bitmap: Bitmap? = VideoProcessor.jpegBytesToBitmap(jpegData)
        bitmap?.let { runOnUiThread { remoteVideoView.setImageBitmap(it) } }
    }

    private fun startAudioStreaming() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT, minBuffer * 4
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT,
            AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT),
            AudioTrack.MODE_STREAM
        )
        audioRecord?.startRecording()
        audioTrack?.play()
        isStreamingAudio = true
        Thread {
            val buffer = ByteArray(minBuffer)
            while (isStreamingAudio) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0 && !isMuted) {
                    bluetoothService.sendData(AUDIO_MARKER + buffer.copyOf(read))
                }
            }
        }.start()
    }

    private fun handleIncomingAudio(data: ByteArray) {
        audioTrack?.write(data, 0, data.size)
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try { backgroundThread.join() } catch (e: InterruptedException) { }
    }

    private fun stopStreaming() {
        isStreamingVideo = false
        isStreamingAudio = false
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()
        cameraCaptureSession?.close()
        cameraDevice?.close()
        stopBackgroundThread()
    }

    private fun endCall() {
        stopStreaming()
        bluetoothService.stopAll()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        bluetoothService.stopAll()
    }
}
