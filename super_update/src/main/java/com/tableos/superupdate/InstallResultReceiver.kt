package com.tableos.superupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallResultReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.d(TAG, "Installation pending user action")
                // The system installer will handle the user interaction
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "Installation successful")
                // Broadcast success to any listening components
                val successIntent = Intent("com.tableos.superupdate.INSTALL_SUCCESS")
                context.sendBroadcast(successIntent)
            }
            PackageInstaller.STATUS_FAILURE -> {
                Log.e(TAG, "Installation failed: $message")
                // Broadcast failure to any listening components
                val failureIntent = Intent("com.tableos.superupdate.INSTALL_FAILURE").apply {
                    putExtra("error_message", message ?: "Unknown error")
                }
                context.sendBroadcast(failureIntent)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.d(TAG, "Installation aborted by user")
                val abortedIntent = Intent("com.tableos.superupdate.INSTALL_ABORTED")
                context.sendBroadcast(abortedIntent)
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.e(TAG, "Installation blocked: $message")
                val blockedIntent = Intent("com.tableos.superupdate.INSTALL_BLOCKED").apply {
                    putExtra("error_message", message ?: "Installation blocked")
                }
                context.sendBroadcast(blockedIntent)
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Log.e(TAG, "Installation conflict: $message")
                val conflictIntent = Intent("com.tableos.superupdate.INSTALL_CONFLICT").apply {
                    putExtra("error_message", message ?: "Installation conflict")
                }
                context.sendBroadcast(conflictIntent)
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Log.e(TAG, "Installation incompatible: $message")
                val incompatibleIntent = Intent("com.tableos.superupdate.INSTALL_INCOMPATIBLE").apply {
                    putExtra("error_message", message ?: "App incompatible")
                }
                context.sendBroadcast(incompatibleIntent)
            }
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Log.e(TAG, "Installation invalid: $message")
                val invalidIntent = Intent("com.tableos.superupdate.INSTALL_INVALID").apply {
                    putExtra("error_message", message ?: "Invalid APK")
                }
                context.sendBroadcast(invalidIntent)
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "Installation storage error: $message")
                val storageIntent = Intent("com.tableos.superupdate.INSTALL_STORAGE_ERROR").apply {
                    putExtra("error_message", message ?: "Storage error")
                }
                context.sendBroadcast(storageIntent)
            }
            else -> {
                Log.e(TAG, "Unknown installation status: $status, message: $message")
                val unknownIntent = Intent("com.tableos.superupdate.INSTALL_UNKNOWN").apply {
                    putExtra("status", status)
                    putExtra("error_message", message ?: "Unknown status")
                }
                context.sendBroadcast(unknownIntent)
            }
        }
    }
}