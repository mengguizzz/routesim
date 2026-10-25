package com.opensource.routesim.xposed

import android.content.ContentResolver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.location.Location
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * XposedHideEntry.kt
 * Routesim 内置的高阶 LSPosed 隐藏引擎（仅在已安装 LSPosed 并勾选启用时生效，对普通免 Root 用户完全透明）
 *
 * 四大核心隐藏能力：
 * 1. 彻底抹除 Location.isMock() (Android 12+) 与 Location.isFromMockProvider() 标志位，强制返回 false。
 * 2. 拦截 Location.getExtras()，清除可能残留的 mock 敏感标记，保留拟真卫星颗数与信噪比。
 * 3. 拦截 Settings.Secure / Settings.Global 查询，隐藏 mock_location 开启痕迹。
 * 4. 硬件计步器 (TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR) 动态步数注入，使跑步轨迹与手机硬件步数同步增长。
 */
class XposedHideEntry : IXposedHookLoadPackage {

    // 记录上次注入位置时的瞬时速度 (m/s)，用于驱动目标应用内的硬件计步器同步累加
    @Volatile
    private var latestMockSpeedMps: Float = 0f

    @Volatile
    private var lastLocationTimestampMs: Long = 0L

    private var simulatedStepOffset: Float = 0f
    private var lastStepCalcTimeMs: Long = 0L

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 不拦截自身与系统底层核心进程
        val pkg = lpparam.packageName
        if (pkg == "com.opensource.routesim" || pkg == "android") {
            return
        }

        hookLocationMockFlags()
        hookSettingsMockCheck()
        hookStepSensorEvents(lpparam.classLoader)
    }

    /**
     * 1. 拦截 android.location.Location 的所有模拟位置检测方法与字段
     */
    private fun hookLocationMockFlags() {
        try {
            // 拦截 API 18+ 的 isFromMockProvider() -> 强制返回 false
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "isFromMockProvider",
                XC_MethodReplacement.returnConstant(false)
            )

            // 拦截 Android 12 (API 31+) 的 isMock() -> 强制返回 false
            try {
                XposedHelpers.findAndHookMethod(
                    Location::class.java,
                    "isMock",
                    XC_MethodReplacement.returnConstant(false)
                )
            } catch (_: NoSuchMethodError) {
                // Android 11 及以下系统无 isMock() 方法，安全忽略
            }

            // 拦截 setIsFromMockProvider(boolean) 与 setMock(boolean) -> 强制改为传入 false
            try {
                XposedHelpers.findAndHookMethod(
                    Location::class.java,
                    "setIsFromMockProvider",
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args[0] = false
                        }
                    }
                )
            } catch (_: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(
                    Location::class.java,
                    "setMock",
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args[0] = false
                        }
                    }
                )
            } catch (_: Throwable) {}

            // 拦截 getSpeed() 顺便记录当前移动速度，供计步传感器同步使用
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getSpeed",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val speed = (param.result as? Float) ?: 0f
                        latestMockSpeedMps = speed
                        lastLocationTimestampMs = System.currentTimeMillis()
                    }
                }
            )

            // 2. 拦截 Location.getExtras()，剔除任何暴露模拟身份的键值
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getExtras",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val bundle = param.result as? Bundle ?: return
                        val suspiciousKeys = listOf("mockLocation", "isMock", "mock", "testProvider")
                        for (key in suspiciousKeys) {
                            if (bundle.containsKey(key)) {
                                bundle.remove(key)
                            }
                        }
                        if (!bundle.containsKey("satellites")) {
                            bundle.putInt("satellites", Random.nextInt(9, 14))
                        }
                        param.result = bundle
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("Routesim-Xposed: hookLocationMockFlags failed: ${t.message}")
        }
    }

    /**
     * 3. 拦截 Settings.Secure / Settings.System 对 "mock_location" 的读取
     */
    private fun hookSettingsMockCheck() {
        val settingsClasses = listOf(
            "android.provider.Settings\$Secure",
            "android.provider.Settings\$System",
            "android.provider.Settings\$Global"
        )
        for (className in settingsClasses) {
            try {
                val clazz = Class.forName(className)
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getString",
                    ContentResolver::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val key = param.args[1] as? String
                            if (key == "mock_location") {
                                param.result = "0"
                            }
                        }
                    }
                )
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getInt",
                    ContentResolver::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val key = param.args[1] as? String
                            if (key == "mock_location") {
                                param.result = 0
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }
    }

    /**
     * 4. 拦截系统传感器事件分发 (SystemSensorManager$SensorEventQueue.dispatchSensorEvent)，
     *    当目标 App 监听硬件计步器 (TYPE_STEP_COUNTER) 且当前处于模拟跑步移动状态时，自动按步频叠加真实步数！
     */
    private fun hookStepSensorEvents(classLoader: ClassLoader) {
        try {
            val queueClass = XposedHelpers.findClass(
                "android.hardware.SystemSensorManager\$SensorEventQueue",
                classLoader
            )
            XposedHelpers.findAndHookMethod(
                queueClass,
                "dispatchSensorEvent",
                Int::class.javaPrimitiveType,
                FloatArray::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val values = param.args[1] as? FloatArray ?: return
                        if (values.isEmpty()) return

                        val now = System.currentTimeMillis()
                        // 如果最近 5 秒内有模拟移动速度，且速度在跑步/步行合理区间 (0.8 m/s ~ 5.0 m/s，即 2.8 ~ 18 km/h)
                        val isRunningActive = (now - lastLocationTimestampMs < 5000L) &&
                                (latestMockSpeedMps in 0.8f..5.0f)

                        if (isRunningActive) {
                            if (lastStepCalcTimeMs > 0L) {
                                val dtSec = ((now - lastStepCalcTimeMs).coerceIn(0L, 2000L)) / 1000f
                                val speedKmh = latestMockSpeedMps * 3.6f
                                // 与 C++ 引擎同构的生物力学步频公式: SPM = 122 + 4.8 * V(km/h)
                                val cadenceSpm = (122f + 4.8f * speedKmh).coerceIn(130f, 200f)
                                simulatedStepOffset += (cadenceSpm / 60f) * dtSec
                            }
                            lastStepCalcTimeMs = now
                        } else {
                            lastStepCalcTimeMs = now
                        }

                        // 若累计了模拟步数，且当前传感器首值符合计步器特征（大于 0 的整数步数），则将模拟步数叠加进去
                        if (simulatedStepOffset >= 1f && values.size == 1 && values[0] >= 0f) {
                            values[0] = values[0] + simulatedStepOffset.roundToInt()
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }
}