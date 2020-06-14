package com.example.mediacontrol

import android.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.example.mediacontrol.R.layout.activity_main)
        doBindService()
    }

    // Don't attempt to unbind from the service unless the client has received some
    // information about the service's state.
    private var mShouldUnbind = false

    // To invoke the bound service, first make sure that this value
    // is not null.
    private var mBoundService: MediaRemoteService? = null

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            mBoundService = (service as MediaRemoteService.LocalBinder).service

            // Tell the user about this for our demo.
            Toast.makeText(
                this@MainActivity, "Service connected",
                Toast.LENGTH_SHORT
            ).show()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            mBoundService = null
            Toast.makeText(
                this@MainActivity, "Service disconnected",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun doBindService() {
        // Attempts to establish a connection with the service.  We use an
        // explicit class name because we want a specific service
        // implementation that we know will be running in our own process
        // (and thus won't be supporting component replacement by other
        // applications).
        if (bindService(
                Intent(this@MainActivity, MediaRemoteService::class.java),
                mConnection, Context.BIND_AUTO_CREATE
            )
        ) {
            mShouldUnbind = true
        } else {
            Log.e(
                "MY_APP_TAG", "Error: The requested service doesn't " +
                        "exist, or this client isn't allowed access to it."
            )
        }
    }

    private fun doUnbindService() {
        if (mShouldUnbind) {
            // Release information about the service's state.
            unbindService(mConnection)
            mShouldUnbind = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        doUnbindService()
    }
}