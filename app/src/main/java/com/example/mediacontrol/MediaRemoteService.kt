package com.example.mediacontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.ImageLoader
import com.android.volley.toolbox.ImageLoader.ImageContainer
import com.android.volley.toolbox.ImageLoader.ImageListener
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target


class MediaRemoteService : Service() {
    private var mNM: NotificationManager? = null
    private val NOTIFICATION_ID: Int = 13
    private val host = "http://192.168.123.8:3000/"

    @Volatile
    private var shouldRun: Boolean = true

    inner class LocalBinder : Binder() {
        val service: MediaRemoteService
            get() = this@MediaRemoteService
    }

    private val mediaPlayPendingIntent
        get() = PendingIntent.getBroadcast(
            this,
            0,
            Intent(getMediaRemoteAction(MediaRemoteCommand.PLAY)),
            0
        )
    private val mediaPausePendingIntent
        get() = PendingIntent.getBroadcast(
            this,
            0,
            Intent(getMediaRemoteAction(MediaRemoteCommand.PAUSE)),
            0
        )
    private val mediaPrevPendingIntent
        get() = PendingIntent.getBroadcast(
            this,
            0,
            Intent(getMediaRemoteAction(MediaRemoteCommand.PREV)),
            0
        )
    private val mediaNextPendingIntent
        get() = PendingIntent.getBroadcast(
            this,
            0,
            Intent(getMediaRemoteAction(MediaRemoteCommand.NEXT)),
            0
        )

    private enum class MediaRemoteCommand {
        PLAY, PAUSE, PREV, NEXT
    }

    private fun getMediaRemoteAction(command: MediaRemoteCommand): String = when (command) {
        MediaRemoteCommand.PLAY -> getString(R.string.media_remote_play)
        MediaRemoteCommand.PAUSE -> getString(R.string.media_remote_pause)
        MediaRemoteCommand.PREV -> getString(R.string.media_remote_prev)
        MediaRemoteCommand.NEXT -> getString(R.string.media_remote_next)
    }

    private fun getMediaRemoteCommand(action: String?): MediaRemoteCommand = when (action) {
        getString(R.string.media_remote_play) -> MediaRemoteCommand.PLAY
        getString(R.string.media_remote_pause) -> MediaRemoteCommand.PAUSE
        getString(R.string.media_remote_prev) -> MediaRemoteCommand.PREV
        getString(R.string.media_remote_next) -> MediaRemoteCommand.NEXT
        else -> throw Exception("Unknown Action")
    }

    override fun onCreate() {
        mNM = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        showNotification()
        val intentFilter = IntentFilter()
        intentFilter.addAction(getString(R.string.media_remote_play))
        intentFilter.addAction(getString(R.string.media_remote_pause))
        intentFilter.addAction(getString(R.string.media_remote_next))
        intentFilter.addAction(getString(R.string.media_remote_prev))
        intentFilter.addAction(getString(R.string.media_remote_update_status))
        registerReceiver(playbackControlReceiver, intentFilter)
        Thread(Runnable {
            while (shouldRun) {
                updateNotification()
                Thread.sleep(500)
            }
        }).start()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i("MediaRemoteService", "Received start id $startId: $intent")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shouldRun = false
        mNM!!.cancel(NOTIFICATION_ID)
        Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        unregisterReceiver(playbackControlReceiver)
    }

    override fun onBind(intent: Intent): IBinder? {
        return mBinder
    }

    private val mBinder: IBinder = LocalBinder()
    private val CHANNEL_ID = "Media_Control";
    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "MediaControl"
            val descriptionText = "Controls media remotely"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private val playbackControlReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.println(Log.ERROR, "Intent", intent.action)
            if (intent.action == getString(R.string.media_remote_update_status))
                updateNotification()
            else handleMediaRemoteCommand(getMediaRemoteCommand(intent.action))
        }
    }
    private lateinit var currentTrack: MediaRemoteTrack;
    private var failureCount: Int = 0;
    private fun updateNotification() {
        val jsonObjectRequest = JsonObjectRequest(Request.Method.GET, host + "status", null,
            Response.Listener { response ->
                failureCount = 0;
                val track = MediaRemoteTrack(
                    response.getString("title"),
                    response.getString("artist"),
                    response.getString("album")
                )
                notificationLayout.setTextViewText(R.id.titleLabel, track.title)
                notificationLayout.setTextViewText(R.id.artistLabel, track.artist)
                togglePlayPause(response.getBoolean("playing"))
                if (!this::currentTrack.isInitialized || (currentTrack != track))
                    updateThumbnail()
                currentTrack = track
            },
            Response.ErrorListener { _ ->
                if (++failureCount > 5)
                    mNM!!.cancel(NOTIFICATION_ID)
                Log.println(Log.ERROR, "Response", "Error Updating status")
            }
        )
        NetworkSingleton.getInstance(this.applicationContext).apply {
            this.addToRequestQueue(jsonObjectRequest)
            this.requestQueue.start()
        }
    }

    private fun updateThumbnail() {
        try {
            Glide.with(this@MediaRemoteService)
                .asBitmap()
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .load(host + "thumbnail").listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Bitmap>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        notificationLayout.setImageViewResource(
                            R.id.albumArtView,
                            R.drawable.ic_album_art
                        )
                        return true;
                    }

                    override fun onResourceReady(
                        resource: Bitmap?,
                        model: Any?,
                        target: Target<Bitmap>?,
                        dataSource: DataSource?,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (resource != null) {
                            Log.println(Log.ERROR, "resource loaded", "Loaded")
                            notificationLayout.setImageViewBitmap(R.id.albumArtView, resource)
                            mNM!!.notify(NOTIFICATION_ID, notification.build())
                        }
                        return true;
                    }
                }).submit()
        } catch (e: java.lang.Exception) {
            Log.println(Log.ERROR, "ImageLoader exception", e.message)
        }
    }

    private lateinit var notificationLayout: RemoteViews
    private lateinit var notification: NotificationCompat.Builder
    private fun togglePlayPause(play: Boolean = true) {
        if (play) {
            notificationLayout.setViewVisibility(R.id.pauseButton, View.VISIBLE)
            notificationLayout.setViewVisibility(R.id.playButton, View.GONE)
        } else {
            notificationLayout.setViewVisibility(R.id.pauseButton, View.GONE)
            notificationLayout.setViewVisibility(R.id.playButton, View.VISIBLE)
        }
        mNM!!.notify(NOTIFICATION_ID, notification.build())
    }

    private fun handleMediaRemoteCommand(command: MediaRemoteCommand): Unit {
        val req: String = when (command) {
            MediaRemoteCommand.PLAY -> "play"
            MediaRemoteCommand.PAUSE -> "pause"
            MediaRemoteCommand.PREV -> "prev"
            MediaRemoteCommand.NEXT -> "next"
        }
        val request = StringRequest(Request.Method.POST, host + req,
            Response.Listener<String> { updateNotification() },
            Response.ErrorListener { Log.println(Log.ERROR, "post req", "error") });
        NetworkSingleton.getInstance(this).apply {
            this.addToRequestQueue(request)
            this.requestQueue.start()
        }
    }

    private fun showNotification() {
        //Log.println(Log.ERROR, "init","Service started")
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java), 0
        )
        notificationLayout =
            RemoteViews(packageName, com.example.mediacontrol.R.layout.notification_small)
        notification = NotificationCompat.Builder(this, CHANNEL_ID)
        notificationLayout.setViewVisibility(R.id.pauseButton, View.GONE)
        notificationLayout.setOnClickPendingIntent(R.id.pauseButton, mediaPausePendingIntent)
        notificationLayout.setOnClickPendingIntent(R.id.playButton, mediaPlayPendingIntent)
        notificationLayout.setOnClickPendingIntent(R.id.nextButton, mediaNextPendingIntent)
        notificationLayout.setOnClickPendingIntent(R.id.prevButton, mediaPrevPendingIntent)
        notification.setSmallIcon(com.example.mediacontrol.R.drawable.ic_music)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(notificationLayout)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
        createNotificationChannel()
        mNM!!.notify(NOTIFICATION_ID, notification.build())
    }

    class NetworkSingleton constructor(context: Context) {
        companion object {
            @Volatile
            private var INSTANCE: NetworkSingleton? = null
            fun getInstance(context: Context) =
                INSTANCE ?: synchronized(this) {
                    INSTANCE ?: NetworkSingleton(context).also {
                        INSTANCE = it
                    }
                }
        }

        val imageLoader: ImageLoader by lazy {
            ImageLoader(requestQueue,
                object : ImageLoader.ImageCache {
                    private val cache = LruCache<String, Bitmap>(50)
                    override fun getBitmap(url: String): Bitmap {
                        Log.println(Log.ERROR, "getBitMap", cache.get(url).byteCount.toString())
                        return cache.get(url)
                    }

                    override fun putBitmap(url: String, bitmap: Bitmap) {
                        cache.put(url, bitmap)
                    }
                })
        }
        val requestQueue: RequestQueue by lazy {
            Volley.newRequestQueue(context.applicationContext)
        }

        fun <T> addToRequestQueue(req: Request<T>) {
            requestQueue.add(req)
        }
    }
}