package org.rombench.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import org.rombench.R

/** A runtime permission with the explanation shown before asking for it. */
data class Perm(
    val name: String,
    @StringRes val label: Int,
    @StringRes val why: Int,
    val minSdk: Int = 1,
    val maxSdk: Int = Int.MAX_VALUE,
) {
    val applies: Boolean get() = Build.VERSION.SDK_INT in minSdk..maxSdk
}

object Perms {
    val LOCATION = Perm(Manifest.permission.ACCESS_FINE_LOCATION, R.string.perm_location, R.string.perm_location_why)
    val BT_SCAN = Perm(Manifest.permission.BLUETOOTH_SCAN, R.string.perm_bt_scan, R.string.perm_bt_scan_why, minSdk = 31)
    val BT_CONNECT = Perm(Manifest.permission.BLUETOOTH_CONNECT, R.string.perm_bt_connect, R.string.perm_bt_connect_why, minSdk = 31)
    val PHONE = Perm(Manifest.permission.READ_PHONE_STATE, R.string.perm_phone, R.string.perm_phone_why)
    val SMS_SEND = Perm(Manifest.permission.SEND_SMS, R.string.perm_sms_send, R.string.perm_sms_send_why)
    val SMS_RECEIVE = Perm(Manifest.permission.RECEIVE_SMS, R.string.perm_sms_receive, R.string.perm_sms_receive_why)
    val MIC = Perm(Manifest.permission.RECORD_AUDIO, R.string.perm_mic, R.string.perm_mic_why)
    val CAMERA = Perm(Manifest.permission.CAMERA, R.string.perm_camera, R.string.perm_camera_why)
    val NOTIFICATIONS = Perm(Manifest.permission.POST_NOTIFICATIONS, R.string.perm_notifications, R.string.perm_notifications_why, minSdk = 33)

    fun granted(ctx: Context, p: Perm): Boolean =
        !p.applies || ContextCompat.checkSelfPermission(ctx, p.name) == PackageManager.PERMISSION_GRANTED

    fun granted(ctx: Context, name: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, name) == PackageManager.PERMISSION_GRANTED

    fun missing(ctx: Context, list: List<Perm>): List<Perm> =
        list.filter { it.applies && !granted(ctx, it) }.distinctBy { it.name }

    /** Development permissions granted with `adb shell pm grant` (never asked at runtime). */
    fun hasDump(ctx: Context) = granted(ctx, Manifest.permission.DUMP)
    fun hasReadLogs(ctx: Context) = granted(ctx, Manifest.permission.READ_LOGS)
}

/** SKIP result for a check that could not run because [missing] permissions are not granted. */
fun Area.skippedForPermission(name: String, title: Txt, missing: List<Perm>): TestResult =
    result(
        name, title, Status.SKIP,
        txt(R.string.skip_permission),
        txt(R.string.skip_permission_reason, missing.joinToString { it.name.substringAfterLast('.') }),
    )
