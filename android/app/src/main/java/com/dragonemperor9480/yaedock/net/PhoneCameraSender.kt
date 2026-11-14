package com.dragonemperor9480.yaedock.net

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Display
import android.view.Surface

/**
 * Films with one of this phone's cameras for the PC, on its own thread: the
 * pictures go through the phone's H.264 encoder and each is handed to [send]
 * behind the byte that says what the frame is, with the most bytes that may
 * already be waiting to go out. [send] answers whether it took the picture;
 * after one it did not, the next it gets is a keyframe. [failed]
 * is called when the camera cannot be used or is taken away. The app must
 * hold `CAMERA` before one is made.
 */
class PhoneCameraSender(
    context: Context,
    private val front: Boolean,
    private val height: Int,
    private val send: (frame: ByteArray, mostWaiting: Long) -> Boolean,
    private val failed: () -> Unit,
) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("phone-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var encoder: MediaCodec? = null
    private var input: Surface? = null
    private var camera: CameraDevice? = null

    /** The stream's settings, which go before every keyframe so the PC can start at any of them. */
    private var settings = ByteArray(0)

    /** Whether pictures are dropped until the next keyframe. */
    private var wantsKeyframe = false

    /** What the encoder is asked for now; it follows what the link carries. */
    private val mostBitRate = Quality.most(height)
    private var bitRate = mostBitRate
    private var bitRateSetAt = 0L

    /** When the newest picture the PC says it has was filmed; negative before it has said. */
    @Volatile private var seenUs = -1L

    /** When the newest picture sent was filmed. */
    private var sentUs = 0L

    /** Whether pictures are held back until the PC has caught up. */
    private var holding = false
    private var stopped = false

    init {
        handler.post(::start)
    }

    /** Stops filming and lets go of the camera. */
    fun stop() {
        handler.post {
            stopped = true
            runCatching { camera?.close() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { input?.release() }
            camera = null
            encoder = null
            input = null
        }
        thread.quitSafely()
        thread.join(1_000)
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        try {
            val id = cameraId(cameras, front) ?: throw IllegalStateException("no such camera")
            // A size the camera can film at, or the encoder would be handed pictures it cannot take.
            val sizes = cameras.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaCodec::class.java)
                .orEmpty()
                .map { it.width to it.height }
            val (width, lines) = size(sizes, height)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, lines).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_EVERY_S)
                // No B-frames: every picture can be shown as it arrives.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // Hints that this is live: encode each picture as it comes, ahead of other work.
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { encoder = it }
            encoder.setCallback(Encoded(), handler)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = encoder.createInputSurface().also { input = it }
            encoder.start()
            cameras.openCamera(id, Opened(input), handler)
        } catch (e: Exception) {
            fail("the camera could not start", e)
        }
    }

    private fun fail(what: String, e: Throwable? = null) {
        if (stopped) return
        stopped = true
        Log.w(TAG, what, e)
        failed()
    }

    private inner class Opened(private val input: Surface) : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            if (stopped) return device.close()
            camera = device
            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(input)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FRAME_RATE, FRAME_RATE))
                }.build()
                val filming = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (stopped) return
                        runCatching { session.setRepeatingRequest(request, null, handler) }
                            .onFailure { fail("the camera could not film", it) }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) = fail("the camera refused the encoder")
                }
                @Suppress("DEPRECATION") // The newer call needs API 28.
                device.createCaptureSession(listOf(input), filming, handler)
            } catch (e: Exception) {
                fail("the camera could not film", e)
            }
        }

        // Another app took the camera, or the phone's policy closed it.
        override fun onDisconnected(device: CameraDevice) {
            device.close()
            fail("the camera was taken away")
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            fail("the camera stopped with error $error")
        }
    }

    private inner class Encoded : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (stopped) return
            try {
                val bytes = ByteArray(info.size)
                codec.getOutputBuffer(index)?.apply {
                    position(info.offset)
                    get(bytes)
                }
                codec.releaseOutputBuffer(index, false)
                when {
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> settings = bytes
                    bytes.isEmpty() -> {}
                    else -> picture(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, info.presentationTimeUs)
                }
            } catch (e: Exception) {
                fail("the encoder stopped", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = fail("the encoder stopped", e)

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    /** Tells the sender how far the PC has got: when the newest picture it has was filmed. */
    fun seen(filmedUs: Long) {
        seenUs = filmedUs
    }

    private fun picture(bytes: ByteArray, key: Boolean, filmedUs: Long) {
        // What the PC shows must not grow old. When it is too far behind what
        // is filmed now, nothing more is sent until it has all that was sent:
        // pictures on their way cannot be taken back, only not added to.
        val wasHolding = holding
        holding = Behind.holds(holding, filmedUs, sentUs, seenUs)
        // The PC has caught up: carry on from a keyframe, asked for now.
        if (wasHolding && !holding) requestKeyframe()
        val dropped = when {
            holding -> true
            wantsKeyframe && !key -> return
            else -> !send(framed(bytes, if (key) settings else ByteArray(0), key, filmedUs), Quality.mostWaiting(bitRate))
        }
        if (!dropped) sentUs = filmedUs
        val now = SystemClock.elapsedRealtime()
        // A link too slow for the video gets less of it, and more again once it has kept up a while.
        val beganDropping = dropped && !wantsKeyframe
        val next = Quality.next(bitRate, beganDropping, if (dropped) 0 else now - bitRateSetAt, mostBitRate)
        // While holding, the keyframe is asked for once the PC has caught up.
        val wantsOneNow = dropped && !holding
        wantsKeyframe = dropped
        runCatching {
            encoder?.setParameters(
                Bundle().apply {
                    if (next != bitRate) putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, next)
                    // A keyframe now instead of waiting for the next one due.
                    if (wantsOneNow) putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                },
            )
        }
        if (next != bitRate) {
            bitRate = next
            bitRateSetAt = now
        }
    }

    private fun requestKeyframe() {
        runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** Whether the PC is too far behind to be sent more. */
    object Behind {
        /** How old the PC's newest picture may be, set against the one filmed now. */
        const val MOST_US = 500_000L

        /**
         * Whether to hold pictures back: from when the PC's newest ([seenUs],
         * negative before it has said) is more than [MOST_US] older than the
         * one filmed now, until the PC has everything sent ([sentUs]).
         */
        fun holds(holding: Boolean, filmedUs: Long, sentUs: Long, seenUs: Long): Boolean = when {
            seenUs < 0 -> false
            holding -> seenUs < sentUs
            else -> seenUs < sentUs && filmedUs - seenUs > MOST_US
        }
    }

    /** How much video the encoder makes, in bits a second. */
    object Quality {
        const val LEAST = 150_000

        /** The most worth spending on pictures of [height] lines: a smaller picture needs less to look as good. */
        fun most(height: Int): Int = when {
            height >= 720 -> 3_000_000
            height >= 480 -> 1_500_000
            height >= 360 -> 900_000
            else -> 500_000
        }
        private const val STEADY_MS = 4_000L

        /** A quarter of a second of video at [rate], in bytes: more than that waiting is video sent late. */
        fun mostWaiting(rate: Int): Long = (rate / 8 / 4).toLong().coerceAtLeast(12_000)

        /**
         * The rate after [rate]: well less when the link just began dropping
         * pictures, a little more when it has gone [sinceChangeMs] without.
         */
        fun next(rate: Int, beganDropping: Boolean, sinceChangeMs: Long, most: Int): Int = when {
            beganDropping -> (rate * 6 / 10).coerceAtLeast(LEAST)
            sinceChangeMs >= STEADY_MS -> (rate * 115 / 100).coerceAtMost(most)
            else -> rate
        }
    }

    companion object {
        private const val TAG = "PhoneCameraSender"

        /** The first byte of a frame holding one of the camera's pictures. */
        const val KIND: Byte = 3
        /** The picture sizes offered, by their lines. */
        val HEIGHTS = listOf(720, 480, 360, 240)

        /**
         * The size to film at for [height] lines, from those the camera has
         * ([sizes], width to height): the widest of that height up to 16:9,
         * else the largest that is smaller, else 1280×720.
         */
        fun size(sizes: List<Pair<Int, Int>>, height: Int): Pair<Int, Int> {
            val usable = sizes.filter { (w, h) -> w % 2 == 0 && h % 2 == 0 && w >= h && w * 9 <= h * 16 }
            return usable.filter { it.second == height }.maxByOrNull { it.first }
                ?: usable.filter { it.second < height }.maxByOrNull { it.second * 10_000 + it.first }
                ?: (1280 to 720)
        }
        const val FRAME_RATE = 30
        private const val KEYFRAME_EVERY_S = 1

        private const val HEADER = 10

        /**
         * One frame for the PC: [KIND], whether it is a keyframe, when it was
         * filmed in microseconds (big-endian), then a keyframe's [settings]
         * and the [picture].
         */
        fun framed(picture: ByteArray, settings: ByteArray, key: Boolean, filmedUs: Long): ByteArray {
            val frame = ByteArray(HEADER + settings.size + picture.size)
            frame[0] = KIND
            frame[1] = if (key) 1 else 0
            for (byte in 0 until 8) frame[2 + byte] = (filmedUs ushr (8 * (7 - byte))).toByte()
            settings.copyInto(frame, HEADER)
            picture.copyInto(frame, HEADER + settings.size)
            return frame
        }

        private fun cameraId(cameras: CameraManager, front: Boolean): String? {
            val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            return cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            }
        }

        /** Whether this phone has a camera on that side. */
        fun has(context: Context, front: Boolean): Boolean =
            runCatching { cameraId(context.getSystemService(CameraManager::class.java), front) != null }.getOrDefault(false)

        /**
         * How far the PC must turn that camera's pictures clockwise to stand
         * them upright, in degrees, for the way the phone is held now.
         */
        fun rotation(context: Context, front: Boolean): Int {
            val cameras = context.getSystemService(CameraManager::class.java)
            val sensor = runCatching {
                cameraId(cameras, front)?.let { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION) }
            }.getOrNull() ?: return 0
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val held = when (display?.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            return turn(sensor, held, front)
        }

        /** [rotation] for a sensor mounted at [sensor] degrees on a screen turned [held] degrees. */
        fun turn(sensor: Int, held: Int, front: Boolean): Int =
            if (front) (sensor + held) % 360 else (sensor - held + 360) % 360
    }
}
