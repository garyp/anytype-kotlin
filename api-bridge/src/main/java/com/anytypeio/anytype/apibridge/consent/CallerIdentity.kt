package com.anytypeio.anytype.apibridge.consent

import android.app.Activity
import android.os.Process
import androidx.annotation.RequiresApi

data class Caller(val uid: Int, val packageName: String)

/**
 * Who launched the consent screen, as the system reports it. Never read from Intent extras:
 * any app can put any package name there.
 */
fun interface CallerIdentity {
    fun resolve(activity: Activity): Caller?

    companion object {
        /**
         * Uses [Activity.getInitialCaller], which carries a real uid and package only when the
         * launcher opted in with `ActivityOptions.setShareIdentityEnabled(true)`.
         */
        @RequiresApi(35)
        val Default = CallerIdentity { activity ->
            val initial = activity.initialCaller
            val uid = initial.uid
            val pkg: String? = initial.`package`
            when {
                uid == Process.INVALID_UID -> null
                pkg == null -> null
                activity.packageManager.getPackagesForUid(uid)?.contains(pkg) != true -> null
                activity.callingPackage != null && activity.callingPackage != pkg -> null
                else -> Caller(uid, pkg)
            }
        }
    }
}
