package com.example.entify_again.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.entify_again.util.PrivacyLog as Log

object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimizationHelper"

    /**
     * 배터리 사용량 제한 없음(최적화 제외) 상태인지 확인
     */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true
    }

    /**
     * 배터리 최적화 해제 요청
     * 1순위: 시스템 원터치 허용 팝업 (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
     * 2순위: 기기별 설정 화면 (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
     * 3순위: 앱 상세 정보 화면 (ACTION_APPLICATION_DETAILS_SETTINGS)
     */
    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct request failed, trying settings screen: ${e.message}")
                try {
                    val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                } catch (e2: Exception) {
                    try {
                        val appDetailIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(appDetailIntent)
                    } catch (e3: Exception) {
                        Log.e(TAG, "All battery optimization intents failed", e3)
                    }
                }
            }
        }
    }
}
