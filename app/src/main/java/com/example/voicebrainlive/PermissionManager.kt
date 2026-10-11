package com.example.voicebrainlive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Centered helper class for Runtime Permission flows (Task 1.1).
 * Checks, prompts rationales, handles SYSTEM_ALERT_WINDOW, and redirects to app Settings.
 */
class PermissionManager(private val activity: Activity) {

    /**
     * Check if a single permission is granted.
     */
    fun hasPermission(permission: String): Boolean {
        if (permission == Manifest.permission.SYSTEM_ALERT_WINDOW) {
            return hasSystemAlertWindowPermission()
        }
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if all required permissions are granted.
     */
    fun hasAllPermissions(vararg permissions: String): Boolean {
        return permissions.all { hasPermission(it) }
    }

    /**
     * Returns true if we should show a permission rationale UI.
     */
    fun shouldShowRationale(permission: String): Boolean {
        if (permission == Manifest.permission.SYSTEM_ALERT_WINDOW) return true
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    /**
     * Display a custom explanation dialog before requesting permission.
     */
    fun showRationaleDialog(
        title: String,
        message: String,
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("ခွင့်ပြုမည်") { _, _ -> onPositive() }
            .setNegativeButton("မလုပ်ပါ") { dialog, _ ->
                dialog.dismiss()
                onNegative?.invoke()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Show a dialog guiding the user to App Settings when permission is persistently denied.
     */
    fun showSettingsNavigationDialog(
        title: String,
        message: String
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Settings သို့သွားမည်") { _, _ ->
                navigateToSettings()
            }
            .setNegativeButton("မသွားပါ", null)
            .show()
    }

    /**
     * Launch the app details settings activity.
     */
    fun navigateToSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e("PermissionManager", "Failed to open settings: ${e.message}")
        }
    }

    /**
     * Check for Draw over other apps permission.
     */
    fun hasSystemAlertWindowPermission(): Boolean {
        return Settings.canDrawOverlays(activity)
    }

    /**
     * Request draw over other apps permission.
     */
    fun requestSystemAlertWindowPermission(requestCode: Int = 9081) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Log.e("PermissionManager", "Failed to start overlay settings with package URI: ${e.message}")
            // Fallback: open general settings list without package URI
            try {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivityForResult(fallbackIntent, requestCode)
            } catch (ex: Exception) {
                Log.e("PermissionManager", "Failed to start general overlay settings: ${ex.message}")
            }
        }
    }

    companion object {
        private const val TAG = "PermissionManager"

        /**
         * Static utility to check permission on general Context.
         */
        fun hasPermission(context: Context, permission: String): Boolean {
            if (permission == Manifest.permission.SYSTEM_ALERT_WINDOW) {
                return Settings.canDrawOverlays(context)
            }
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
