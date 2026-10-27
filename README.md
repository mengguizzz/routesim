# Routesim — Android 路线运动仿真与定位调试工具

**Routesim** 是一款基于 **C++17 原生运动学引擎 + Kotlin** 构建的开源 Android 路线运动仿真与定位调试工具。它支持在地图上单指自由绘制运动轨迹，按**跑步、骑行、驾车**三种物理模型实时生成高拟真定位与步频样本，并提供 **“免 Root 开发者模式 / Root 增强模式 / 内置 LSPosed 深度隐藏模块”** 三级自适应运行架构。

* **下载**：[点击下载](https://github.com/mengguizzz/routesim/release)

> **严正免责声明与合规使用边界**
> 1. 本项目仅面向开发者、测试人员与安全研究人员，仅限在**您自己拥有或明确获准测试的设备**上用于地理围栏（Geofence）开发测试、LBS 业务逻辑调试、轨迹回放研究与个人位置隐私保护。
> 2. **严禁将本软件用于任何企业考勤打卡、外卖/配送考核、体育赛事/校园跑作弊、伪造社交轨迹、欺诈或任何违反当地法律法规及第三方平台服务条款的用途。**
> 3. 使用虚拟定位可能违反第三方应用的服务条款并导致账号封禁或限制。任何个人或组织因不当使用、滥用本项目源码或编译产物而产生的一切法律责任、账号损失及连带风险，均由**使用者本人全权承担**，与项目作者及贡献者无关。

## 界面预览

<img width="240" alt="主界面" src="https://github.com/user-attachments/assets/a224ad58-9d5c-448c-b21d-728ceb25aa55" />

<img width="240" alt="参数选择" src="https://github.com/user-attachments/assets/32d20621-b367-4687-b582-73e129ca8b74" />

<img width="240" alt="模拟过程" src="https://github.com/user-attachments/assets/d22f9df4-11b3-4be8-994d-3d0fa2049ec9" />

## 核心特性

### 1. C++17 原生生物力学与运动学仿真引擎 (`kinematics_engine.cpp`)
* **双随机平滑变速模型**：告别传统虚拟定位“速度一条死直线”的机械特征。支持自定义速度随机区间与单段保持时长区间，每次随机抽取目标配速与维持时长，并以符合人体/车辆物理极限的平滑加减速过渡。
* **步频与配速生物力学耦合**：跑步模式下根据瞬时速度实时解算步频（经验耦合模型 $SPM = 122 + 4.8 \times V(\text{km/h}) + \text{高斯生理波动}$，约落在 `132–198 spm`），采用亚步数累加器精确同步累计总步数；骑行模式实时解算并展示踏频。
* **弯道前瞻自动减速**：骑行与驾车模式内置路径前瞻转角计算，遇到急弯自动平滑降低目标巡航速度，出弯后恢复加速。
* **多圈往返循环回放**：支持自定义目标圈数（`1–999` 圈，默认 `1` 圈）。单向完整跑完一次计 1 圈；当目标圈数设为 3 时，自动按 `A → B`、`B → A`、`A → B` 往返完成三圈后精准停止。
* **单调时钟推进与 JNI 符号隐藏**：使用单调时钟推进物理时间（主线程偶发卡顿只会减少采样次数，绝不会让运动变成慢动作）；C++ 层采用 `JNI_OnLoad` + `RegisterNatives` 动态注册并开启 `-fvisibility=hidden`，`.so` 动态符号表零业务函数名暴露。

### 2. 三级自适应运行与隐藏架构（单 APK 全兼容）
同一个安装包可根据设备权限环境自动或手动切换三种工作级别：

| 运行级别 | 适用设备环境 | 核心机制与隐藏能力 |
| :--- | :--- | :--- |
| **Level 1：免 Root 开发者模式** | 任何未 Root 的普通 Android 8.0+ 手机或模拟器 | 无需 Root 权限即可直接进入地图；内置**动态正弦起伏海拔（`33.5–38.5m`）**、**动态卫星颗数（`7–15` 颗）**与**动态载噪比（`Cn0`）**，补齐 Android 8.0–14 全套硬件精度字段并在本进程推送前反射预清理 `mFieldsMask` 模拟位。 |
| **Level 2：Root 增强模式** | 已刷入 [KernelSU](https://github.com/tiann/KernelSU) / [Magisk](https://github.com/topjohnwu/magisk) / APatch 的设备 | 顶部状态胶囊支持**一键实时重测与开关 Root 模式**；启动时通过 `su` 自动执行 `appops` 静默授予模拟定位与省电白名单（无需手动去开发者选项勾选）；停止模拟瞬间自动触发 **`AppOps` 阅后即焚**（将 `android:mock_location` 重置回 `default`），不留常驻授权痕迹。 |
| **Level 3：内置 [LSPosed](https://www.google.com/search?q=https://github.com/LSPosed/LSPosed) 深度隐藏模块** | 已安装 KernelSU/Magisk + Zygisk + LSPosed 的设备 | 同一个 APK 内置 `XposedHideEntry` 模块（未装 LSPosed 时完全静默）：<br>1. 拦截目标应用的 [Location.isMock()](https://www.google.com/search?q=https://developer.android.com/reference/android/location/Location%23isMock%28%29) 与 [Location.isFromMockProvider()](https://www.google.com/search?q=https://developer.android.com/reference/android/location/Location%23isFromMockProvider%28%29) 强制返回 `false`；<br>2. 自动清洗 `Location.getExtras()` 中的敏感标记；<br>3. 拦截 `Settings.Secure` / `Global` 隐藏 `mock_location` 开启状态；<br>4. **硬件计步器同步**：拦截目标应用内的 `SensorEventQueue`，在跑步模拟期间将实时步频同步叠加至手机硬件计步传感器（`TYPE_STEP_COUNTER`）。 |

### 3. 丝滑地图交互与工业级后台保活
* **自定义手绘手势图层 (`RouteDrawingOverlay`)**：支持**单指连续画线**、**按笔迹一键撤回**、**双指平移/缩放**、**双击放大**以及**一键缩放查看全程路线**。
* **双瓦片源与多主题 (`AppTileSources`)**：默认接入国内网络直连更快的 [OpenStreetMap 德国镜像源 (`tile.openstreetmap.de`)](https://tile.openstreetmap.de/)，支持一键切换回 [OSM 官方瓦片源](https://www.google.com/search?q=https://www.openstreetmap.org/)；内置浅色、深色、跟随系统三套 Material 3 外观主题。
* **真实初始位置记忆**：启动主页后自动请求最新真实定位并居中显示；若室内无信号则回退至上次成功的真实位置，拒绝硬编码回退固定城市坐标。
* **全天候保活服务 (`SimulationService`)**：采用前台常驻通知栏服务 + CPU 部分唤醒锁（`Partial WakeLock`）+ 屏幕常亮锁，模拟期间切到被测 App 也不会断联或降频，停止模拟后自动释放所有锁资源。
* **自带独立探针应用 (`locationprobe`)**：工程内置一个零地图、零 Root 依赖的只读定位测试 App，专门用于站在第三方普通应用的视角检验 `Routesim` 的定位投递与隐藏是否生效。

## 运动物理模型预设参数

| 运动模式 | 默认速度区间 | 默认单段保持时长 | 核心动力学行为特征 |
| :--- | :--- | :--- | :--- |
| **跑步 (Running)** | `5.0 – 15.0 km/h` | `8 – 35 秒` | 双随机平滑变速、加速度限幅、速度联动步频（`132–198 spm`）、累计计步、摆臂级微抖动 |
| **骑行 (Cycling)** | `14.0 – 28.0 km/h` | `15 – 60 秒` | 准匀速随机巡航、前瞻弯道自动减速、实时踏频解算显示 |
| **驾车 (Driving)** | `25.0 – 75.0 km/h` | `12 – 45 秒` | 更高加减速响应上限、弯道前瞻制动减速、道路级轨迹推进 |

## 使用方式

### 第一步：选择适合你设备的权限模式

#### 模式 A：普通免 Root 用户（零门槛调试）
1. 安装并打开 `Routesim`，首次启动请认真阅读合规声明（需阅读满 5 秒、滑动到底部并输入确认语句 `我已阅读同意隐私政策`）。
2. 顶部状态栏会显示 **`免 ROOT (点击开 Root)`**。
3. 打开手机 **系统设置 -> 开发者选项 -> 选择模拟位置信息应用**，选中 `Routesim`。

#### 模式 B：Root 用户（KernelSU / Magisk 自动提权与阅后即焚）
1. 打开手机上的 **KernelSU** 或 **Magisk** 管理器，在“超级用户”列表中为 `Routesim` 开启 Root 授权。
2. 如果你是先打开了 `Routesim` 再去开启的 KernelSU 授权，无需重启软件，直接**点击 `Routesim` 界面顶部的状态胶囊按钮**，即可实时重测并切换为 **`ROOT 增强 (点击关闭)`**。
3. 在此模式下，软件会自动通过 `appops` 完成底层模拟定位授权，停止模拟时自动恢复默认状态。

#### 模式 C：Root + LSPosed 用户（开启深度隐藏与硬件计步器同步）
1. 确保手机已在 KernelSU / Magisk 中安装 [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext) 与 [LSPosed](https://www.google.com/search?q=https://github.com/LSPosed/LSPosed)。
2. 安装 `Routesim` 后，打开 **LSPosed 管理器 -> 模块 -> Routesim**，打开 **“启用模块”** 开关。
3. 在作用域列表中**勾选你需要测试的目标应用**（例如配套的 `LocationProbe` 探针应用，注意**不要勾选 `Routesim` 自身**）。
4. 将目标应用从手机后台彻底划掉（或点击“强行停止”）后重新打开，深度隐藏与计步同步即刻生效。

### 第二步：绘制路线并开始运动模拟
1. 授予软件定位与通知权限，地图会自动定位到你当前的所在位置。
2. 在地图画布上**用单指滑动或点选**绘制至少 2 个路径点（画错可点右侧“撤回”或“清除”）。
3. 点击底部控制栏选择 **跑步 / 骑行 / 驾车** 模式；点击展开按钮可拖动双滑块微调**速度范围**、**单段保持时间**以及**目标圈数**。
4. 点击 **“开始模拟”**，此时可切换到 `LocationProbe` 或其他被测应用观察实时轨迹与步数变化；跑完目标圈数后将自动停止并清理测试 Provider。

## 项目架构

```text
routesim/
├── app/                                   # 主应用模块 (Routesim + 内置 LSPosed 模块)
│   ├── src/main/cpp/
│   │   ├── CMakeLists.txt                 # NDK C++17 构建配置 (默认开启符号隐藏)
│   │   └── kinematics_engine.cpp          # C++17 核心运动学引擎 + JNI_OnLoad 动态注册
│   ├── src/main/assets/
│   │   └── xposed_init                    # LSPosed 模块入口声明文件
│   └── src/main/java/com/opensource/routesim/
│       ├── MainActivity.kt                # 地图画布、HUD 仪表盘、模式参数与 Root 胶囊开关
│       ├── service/SimulationService.kt   # 前台保活服务、单调采样时钟、通知栏与唤醒锁
│       ├── core/
│       │   ├── RootMockManager.kt         # 双模式自适应管理、动态物理信号与 AppOps 阅后即焚
│       │   ├── NativeKinematics.kt        # JNI 原生引擎桥接与状态快照解包
│       │   ├── SimulationConfig.kt        # 运动模式、速度/保持时间区间与圈数配置模型
│       │   └── SimulationLogger.kt        # 结构化调试日志输出
│       ├── ui/
│       │   ├── RouteDrawingOverlay.kt     # 单指连续画线、笔迹撤回与双指缩放自定义图层
│       │   ├── AppTileSources.kt          # OSM 官方瓦片与德国高速镜像源配置
│       │   └── PrivacyConsentDialog.kt    # 首次启动强制阅读与短语确认合规门禁
│       └── xposed/
│           └── XposedHideEntry.kt         # LSPosed 隐藏引擎 (抹除 isMock + 同步计步传感器)
└── locationprobe/                         # 独立的只读定位验证器 App (用于交叉检验模拟效果)
```

## 从源码构建与调试

### 编译环境要求
* **IDE 与 JDK**：Android Studio (自带 JBR) 或 JDK 17+
* **Android SDK**：`compileSdk 37` / `minSdk 26` (Android 8.0+)
* **Native 工具链**：Android NDK & CMake 3.22.1+（支持编译 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` 全架构通用包）

### 命令行构建示例 (Windows PowerShell)

```powershell
# 设置 JAVA_HOME 与 Gradle 缓存目录（请根据本机路径调整）
$env:JAVA_HOME = 'D:\Program Files\AndroidStudio\jbr'
$env:GRADLE_USER_HOME = 'E:\routesim-work\gradle'

# 编译主应用与探针应用的 Debug APK
.\gradlew.bat :app:assembleDebug :locationprobe:assembleDebug

# 运行单元测试与仪器测试构建
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

编译完成后，生成的通用安装包位于：
`app/build/outputs/apk/debug/app-debug.apk`

### 查看结构化调试日志
连接手机后，可通过以下命令实时查看 `Routesim` 的内部状态机与定位推送日志：

```bash
adb logcat -s RouteSim:V
```

## 隐私政策与数据安全说明

`Routesim` 严格遵循最小权限与本地化处理原则（完整说明详见仓库中的 `PRIVACY.md`）：

* **零云端收集**：本应用**不包含**任何账号系统、广告 SDK、第三方统计分析（Telemetry）或崩溃自动上传组件。
* **纯本地存储**：手绘路线与实时模拟状态仅保存在当前应用进程内存中；运动参数、瓦片源选择、外观主题及免责声明确认状态仅保存在本机 `SharedPreferences` 中，应用绝不会向开发者或任何第三方主动上传您的位置坐标、设备标识符或个人数据。
* **网络请求透明**：本应用发起的唯一网络请求为向您所选的 [OpenStreetMap 官方](https://www.google.com/search?q=https://www.openstreetmap.org/)或[德国镜像瓦片服务器](https://tile.openstreetmap.de/)请求地图图片瓦片。瓦片服务提供方可能会在常规网络访问日志中看到您的设备 IP 与请求的瓦片区域坐标，请遵守 OpenStreetMap 瓦片使用政策。
* **调试日志说明**：运行期间的结构化诊断信息仅写入本机 Android `logcat` 缓冲区，仅供本机拥有 `adb` 或 `Root` 权限的开发者自行排查问题。

## 许可证 (License)

Copyright (C) 2026 [mengguizzz](https://github.com/mengguizzz) and Routesim contributors.

本项目采用 **[GNU General Public License v3.0 only (GPL-3.0-only)](https://www.gnu.org/licenses/gpl-3.0.html)** 开源许可证进行许可。

您可以自由地使用、研究、修改和分发本项目；但根据 GPL-3.0 协议的传染性条款，任何基于本项目修改或衍生的作品在分发时**必须同样开放完整源代码**，并保留相同的版权声明与自由软件许可条件，严禁将本项目代码闭源商业化售卖。
