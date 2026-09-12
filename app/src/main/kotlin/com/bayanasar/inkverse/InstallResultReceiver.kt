package com.bayanasar.inkverse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Completes a PackageInstaller session.
 *
 * Committing a session does not show the install prompt. The installer answers with
 * STATUS_PENDING_USER_ACTION and an Intent the app has to start itself; without this
 * the download finishes, the session commits, and nothing visible happens.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(
                    Intent.EXTRA_INTENT,
                    Intent::class.java,
                )
                if (confirm == null) {
                    Log.e(TAG, "pending user action with no confirm intent")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
                Log.i(TAG, "showing install confirmation")
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "update installed")
                Toast.makeText(context, "Inkverse updated", Toast.LENGTH_LONG).show()
            }

            else -> {
                Log.w(TAG, "install failed status=$status message=$message")
                Toast.makeText(
                    context,
                    "Update failed: ${message ?: "status $status"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
