# Routesim

Routesim 是一个仅面向 Root 设备的路线运动模拟与定位调试工具。它在地图上绘制路线，
按跑步、骑行或驾车模式生成随时间变化的定位样本，并投递到 Android 定位测试 Provider。

项目主页：<https://github.com/mengguizzz/routesim>

<img width="300" alt="界面" src="https://github.com/user-attachments/assets/216f889e-6bb5-4a03-8068-6c83e51b2916" />

> 本项目仅用于自有设备上的功能测试、路线回放和开发调试。请勿用于考勤、配送考核、
> 赛事、社交轨迹或其他误导他人的用途。虚拟定位可能违反第三方服务条款并导致账号限制。

## 当前功能

- 单指连续画线，双指平移/缩放，双击放大，地图可查看全程。
- 跑步、骑行、驾车三套默认参数，可分别调整速度范围和单段保持时间。
- 随机目标速度、随机保持时长、受模式限制的平滑加速/减速。
- 跑步步频与速度关联，并同步累计步数；骑行显示踏频。
- 驾车和骑行会按路线前瞻角度降低弯道目标速度。
- 目标圈数可输入，默认 1 圈；每次单向完整经过路线计 1 圈。设为 3 时按 A→B、B→A、A→B 依次完成三圈并停止。
- 默认使用当前网络下可达的 OSM 德国镜像，可切换回 OSM 官方瓦片。
- 打开主页后读取最新定位，在地图上显示当前位置；失败时显示上次成功的真实定位，不回退到固定北京坐标。
- 前台服务 + 部分唤醒锁 + 屏幕常亮锁；模拟期间即使切到被测 App，屏幕也会保持点亮，停止后自动释放。
- GPS 与 Network 测试 Provider，包含速度、方位、精度、卫星数等字段。
- 结构化 `RouteSim` 日志，便于通过 `adb logcat` 调试。
- Root 强门禁：验证失败会停止应用退出，不进入功能界面。
- 首次 Root 验证通过后显示隐私政策：至少阅读 5 秒、滑到政策底部并输入确认语句后才能继续。
- 底部“主页 / 关于”导航；主页控制面板可收起或展开，驾驶/骑行参数、目标圈数和运行信息在展开区域中。
- 关于页面内含版本、GitHub、隐私说明、使用边界和浅色/深色/跟随系统主题。

仓库还包含一个独立的 `Location Probe` 测试 App。它不包含地图或 Root 功能，只申请定位
权限并通过 LocationManager 读取位置变化，用于验证 Routesim 对其他普通 App 是否有效。

## 使用方式

1. 在 Root 管理器中允许 Routesim 使用 `su`。
2. 授予定位与通知权限。
3. 在地图上单指画出至少两个路径点。
4. 选择运动模式并调整速度、保持时间与目标圈数。
5. 打开主页时会请求一次最新位置并在地图上显示当前位置。
6. 点击“开始模拟”。运行期间可打开其他 App 验证定位。
7. 达到目标圈数后自动停止；也可以手动停止并清理测试 Provider。

Root 环境会自动执行：

```text
appops set com.opensource.routesim android:mock_location allow
```

因此通常不需要再到开发者选项中手动选择模拟位置应用。开发者选项中的同一入口可以作为
Root 配置失败时的备用修复方式。

## 运动模型

| 模式 | 默认速度 | 默认单段保持 | 行为 |
| --- | --- | --- | --- |
| 跑步 | 5–15 km/h | 8–35 秒 | 随机变速、平滑加减速、速度联动步频、计步 |
| 骑行 | 14–28 km/h | 15–60 秒 | 随机巡航、弯道减速、踏频显示 |
| 驾车 | 25–75 km/h | 12–45 秒 | 更强的加速/制动、弯道前瞻减速 |

时间使用单调时钟推进；主线程卡顿只会减少采样次数，不会让模拟变成慢动作。GPS 样本默认
每秒产生一次。跑步步频约落在 132–198 spm，具体由当前速度决定。

## 构建

环境：

- Android Studio JBR 或 JDK 17+
- Android SDK 37
- NDK/CMake（工程已配置）
- Android 8.0+ 设备

示例：

```powershell
$env:JAVA_HOME='D:\Program Files\AndroidStudio\jbr'
$env:GRADLE_USER_HOME='E:\routesim-work\gradle'
.\gradlew.bat :app:assembleDebug
```

测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

设备日志：

```powershell
adb logcat -s RouteSim:V
```

## 架构

- `MainActivity`：地图、路线编辑、HUD、参数设置、免责声明。
- `SimulationService`：前台模拟生命周期、采样时钟、通知和唤醒锁。
- `RootMockManager`：Root 检测、app-op 配置、测试 Provider 生命周期。
- `RouteDrawingOverlay`：单指画线和双指地图手势。
- `NativeKinematics` / `kinematics_engine.cpp`：路线推进、速度曲线、步频、弯道和循环。
- `locationprobe`：只读定位验证器，不依赖地图和 Root。

详细隐私说明见 [PRIVACY.md](PRIVACY.md)。

## 兼容性与限制

- 系统会把这些位置标记为 mock；这不是绕过检测的工具，也不实现 Root 隐藏、Hook、
  包名伪装或其他反检测机制。
- 不同厂商 ROM 对测试 Provider 的清理行为可能不同。若停止模拟后系统定位异常，可尝试
  关闭再打开定位开关，或重启设备。
- 当前路线只保存在本次界面状态中，尚未实现路线文件导入/导出与持久化。
- 当前地图瓦片来自 OpenStreetMap，需要网络；请遵守其瓦片使用政策。
- 尚未模拟 GNSS NMEA、传感器融合、海拔变化、交通信号等待等更高阶细节。

## 开源前建议

- 保留免责声明，不添加绕过第三方服务风控的功能。
- 明确发布渠道。Root 与模拟定位类应用通常不适合直接提交 Google Play。
- 为不同 ROM、Android 版本和目标定位 API（LocationManager、FusedLocationProvider）
  建立真实设备测试矩阵。

## 许可证

Copyright (C) 2026 mengguizzz and Routesim contributors.

本项目采用 [GNU General Public License v3.0 only](LICENSE)（`GPL-3.0-only`）。
你可以按照许可证条款使用、修改和分发本项目；分发修改版时必须提供对应源代码，
并保留相同的自由软件许可条件。
