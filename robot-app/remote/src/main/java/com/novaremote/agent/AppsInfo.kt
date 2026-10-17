package com.novaremote.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject

// read-only info plus launch/uninstall. uninstall opens the system "Uninstall?" prompt, same as
// tapping it in the launcher - nothing is removed without that confirm.
object AppsInfo {

    // launchable apps on the robot, as a json array the control page renders
    fun installed(context: Context): JSONArray {
        val pm = context.packageManager
        val arr = JSONArray()
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        launchable
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .sorted()
            .forEach { pkg ->
                val o = JSONObject().put("package", pkg)
                runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    o.put("label", pm.getApplicationLabel(ai).toString())
                    val pi = pm.getPackageInfo(pkg, 0)
                    o.put("versionName", pi.versionName ?: "")
                    o.put("system", (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                }
                arr.put(o)
            }
        return arr
    }

    fun launch(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun uninstall(context: Context, pkg: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun battery(context: Context): JSONObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging ?: false
        return JSONObject().put("level", level).put("charging", charging)
    }
}
