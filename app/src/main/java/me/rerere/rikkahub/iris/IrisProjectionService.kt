package me.rerere.rikkahub.iris

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Iris L0 (adapted): a MediaProjection screen mirror running as a mediaProjection-type
 * foreground service. The VirtualDisplay mirrors the screen into an ImageReader; frames
 * are pulled ONLY when a tool asks for them ([acquireFrame] — acquireLatestImage at call
 * time), so the steady-state cost is near zero and the newest frame is at most one
 * refresh old. This is the tool-paced adaptation of the doc's 30fps pipeline: the zero-copy
 * GPU/HEVC streaming path needs a receiving server this app does not have, and saying so
 * is cheaper than pretending.
 *
 * One consent dialog grants the projection; it stays alive until [stop] (or the Stop
 * action on the notification) — unlike the accessibility capture it has no ~1/sec rate
 * limit. FLAG_SECURE surfaces still come back black; callers detect and report that.
 */
class IrisProjectionService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopScreenShare()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (projection != null) return START_STICKY

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = runCatching { mgr.getMediaProjection(resultCode, data) }.getOrNull()
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val dm = resources.displayMetrics
        val r = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 3)
        val d = proj.createVirtualDisplay(
            "iris",
            dm.widthPixels,
            dm.heightPixels,
            dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface,
            null,
            null,
        )
        projection = proj
        reader = r
        display = d
        instance = this
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopScreenShare()
            }
        }, null)
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Iris screen broadcast", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, IrisProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Iris — screen broadcast active")
            .setContentText("Screen tools can capture instantly. Tap Stop to end.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopScreenShare() {
        instance = null
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null
        reader = null
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopScreenShare()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val ACTION_STOP = "me.rerere.rikkahub.iris.STOP"
        private const val CHANNEL = "iris_projection"
        private const val NOTIF_ID = 74773

        @Volatile
        private var instance: IrisProjectionService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, IrisProjectionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, IrisProjectionService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        /**
         * The newest frame the mirror holds, copied into a Bitmap; null when the mirror is
         * not running or momentarily empty. One pixel copy per call — at tool pace this is
         * negligible; it is NOT the doc's zero-copy 30fps path (no receiver for that here).
         */
        fun acquireFrame(): Bitmap? {
            val r = instance?.reader ?: return null
            return runCatching {
                val image: Image = r.acquireLatestImage() ?: return null
                try {
                    val plane = image.planes[0]
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val bw = image.width
                    val bh = image.height
                    val rowPadding = (rowStride - bw * pixelStride) / pixelStride
                    val bmp = Bitmap.createBitmap(bw + rowPadding, bh, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    if (rowPadding == 0) {
                        bmp
                    } else {
                        Bitmap.createBitmap(bmp, 0, 0, bw, bh).also {
                            if (it !== bmp) bmp.recycle()
                        }
                    }
                } finally {
                    image.close()
                }
            }.getOrNull()
        }
    }
}
