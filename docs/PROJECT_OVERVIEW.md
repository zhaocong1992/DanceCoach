# AI幻舞 — 技术总览

> 本文是面向开发者的架构说明。快速上手请看根目录 [README](../README.md)。

---

## 一、项目目标

Android 舞蹈教学辅助应用。用户选择一段舞蹈教学视频，App 在屏幕播放教师视频、同时用前置
摄像头实时拍摄用户，借助 ML Kit 姿态检测对教师与用户分别提取骨架，计算动作相似度并实时反馈，
帮助跟练纠正。

包名 `com.dancecoach.app`。

### 核心体验

- 首页 4 功能入口：`跳舞教学` / `我的舞蹈库` / `舞蹈市场` / `我的`（横竖屏自适应）。
- 教学模式：教师视频 + 用户摄像头同屏；叠加教师（半透明）/ 用户（青色）骨架；顶部实时相似度 +
  「注意：XX 再到位一点」提示；手势控制（抱头退出 / 左右切歌 / 平举重练）。
- 我的舞蹈库：批量导入视频 → 自动校验（竖屏/时长/格式）→ 离线骨架预审 → 评分历史与重玩。
- 舞蹈市场：分享舞蹈视频，支持上传、下载（入库扣费失败回滚）、点赞、收藏、搜索、分类、积分激励。
- 我的：奖励中心（等级/徽章/签到）+ 参数配置（镜像/旋转/抽帧/阈值/延迟）+ 协议/隐私/开源许可。

---

## 二、技术栈

| 项目 | 版本 |
|------|------|
| 语言 | Kotlin 1.6.21 |
| Android SDK | compileSdk 30 / minSdk 21 / targetSdk 30 |
| 构建 | Gradle 6.8.3 + AGP 4.2.2 |
| JDK | 8 |
| 摄像头 | CameraX 1.0.2（仅 ImageAnalysis，无 Preview） |
| 姿态检测 | Google ML Kit Pose Detection `18.0.0-beta3` |
| 播放器 | ExoPlayer 2.15.1 |
| 投屏 | AndroidX MediaRouter 1.2.5（Presentation） |
| 异步 | Kotlin Coroutines 1.5.2 |
| 后端（可选） | 任意 HTTP 网关；默认不配置，走本地 Mock |
| UI | Android View 体系（XML 布局），无 Compose |

构建与安装步骤见 [README](../README.md#快速开始)。

---

## 三、代码结构

```
app/src/main/java/com/dancecoach/app/
├── ui/
│   ├── HomeActivity.kt             # 4 卡片首页 + 横竖屏切换
│   ├── TeachingActivity.kt         # 教学模式编排：双模式、骨架浮层、手势控制、加载对话框
│   ├── TeachingController.kt       # 分段跳控制器（代码保留，模式已禁用）
│   ├── FollowAlongController.kt    # 跟着跳控制器（lag）
│   ├── LibraryActivity.kt          # 舞蹈库（网格 + 导入校验 + 删除 + 转市场）
│   ├── ActionReviewActivity.kt     # 动作预审页（本地/远程两种模式）
│   ├── MarketActivity.kt           # 舞蹈市场列表（loading/空态/失败提示）
│   ├── MarketDetailActivity.kt     # 市场详情 + 内联播放 + 下载（转圈提示）
│   ├── MarketUploadActivity.kt     # 上传分享（协议勾选 + 直传 + 转圈）
│   ├── MarketMyListActivity.kt     # 我的上传/收藏（结算 + 转圈）
│   ├── SettingsActivity.kt         # 参数配置 + 关于 + 协议展示
│   ├── CastManager/CastPresentation/DialogCastDevices  # 投屏
│   └── SkeletonOverlayView.kt      # 骨架叠加（transpose 开关 + worstBone 标红）
├── camera/   CameraManager.kt      # CameraX 抽帧 + 像素级旋转/镜像 + 软件渲染
├── ml/       PoseExtractor.kt / FrameDumper.kt
├── domain/
│   ├── PoseData / PoseUtils / ComparisonEngine / GestureRecognizer / ModePreference / AppPrefs
│   ├── AnalysisManager（动作预审队列）/ Segmenter / DanceSegment / FollowAlongController / TeachingController
│   └── LegalTexts.kt               # 版权行/免责/开源许可/用户协议/隐私政策
│   └── market/
│       ├── MarketRepository        # 接口；CloudMarketRepository + MockMarketRepository
│       ├── CloudConfig             # endpoint/token（BuildConfig 注入，缺省为空）
│       ├── RewardManager / RewardPrefs   # 积分/等级/徽章/签到/被动收益
│       ├── MarketPrefs             # 点赞/收藏/我的上传（本机 SP）
│       ├── MarketLibraryBridge     # 市场视频流式下载 → 本地舞蹈库
│       ├── DeviceIdProvider / AgreementPrefs
└── data/    VideoCacheManager.kt / VideoValidator.kt
```

共 43 个 Kotlin 文件（约 9000 行源码）。

---

## 四、坐标流水线（关键，稳定保持）

整条流水线对所有帧来源**保持坐标空间一致**。核心事实：
ML Kit 返回的坐标处于「送入 bitmap 自身坐标空间」，rotation 只供内部检测、不改变输出坐标系。

```
用户（摄像头）                         教师（视频）
ImageProxy(YUV)                         ExoPlayer 播放中
  ├─ rawBitmap                            ├─ detectLiveFrameAt(t)
  ├─ flip = postScale(-1,1)               │   MediaMetadataRetriever 取帧
  ├─ detect(flipBmp, rotation)            │   uprightBitmap() ← 物理转正
  │   (STREAM_MODE)                       └─ detectTeacherFrame(upright, 0)
  ▼                                          (SINGLE_IMAGE_MODE)
SkeletonOverlayView(transpose=true)     SkeletonOverlayView(transpose=false)
  ▼                                       ▼
ComparisonEngine.compare(user, teacher) ──► 余弦相似度(自动镜像对齐) → 0~100
```

> `transpose` 保留：用户路径 true（抵消横屏原始帧）；教师路径 false（帧已物理转正）。

### YUV 转换必须尊重 stride

`YUV_420_888` 的 `rowStride` 常常大于图像宽度（硬件内存对齐填充），`pixelStride` 也不一定为 1。
按「整块 buffer 直接首尾拼接」的写法在没有填充的设备上碰巧成立，一旦换到有对齐填充的机型
（常见于高通/联发科的中高端设备），画面会出现斜向彩色条纹，姿态检测直接失效。

`CameraManager.imageProxyToBitmap()` 按 `rowStride` 逐行取 Y、按 `pixelStride` 取交错的 V/U
拼成标准 NV21，任何设备都能正确还原。

---

## 五、模式与状态机

| 模式 | 状态 | 说明 |
|------|------|------|
| DEBUG | 默认 | 调试模式：跟跳 + 双角骨架浮层 + 相似度浮层 |
| FOLLOW | 可用 | 跟跳：视频持续播放，用户实时跟跳，仅显示相似度 |
| SEGMENTED | **已禁用** | 分段跳：代码保留未删，仅从切换循环移除入口 |

模式切换：举重手势（双手举过肩 1.5s）在 `DEBUG ↔ FOLLOW` 循环。手势控制（教学页保持 1.5s 确认）：
抱头=退出、左/右手=切歌、双臂平举=重练。

---

## 六、舞蹈市场闭环设计

- **数据**：通过 `MarketRepository` 接口访问后端，动作包括 list / get / upload / reportPlay /
  reportDownload / reportLike / syncMyUploads / getFileUrl / getUploadCredential。视频走对象存储直传。
- **上传**：本地舞蹈库 → 动作预审 → 上传页（协议勾选 + 标题/分类/描述 + 封面抽帧）→ 直传 → 上架。
- **下载**：市场详情 → 流式下载入库（成功才扣积分，失败回滚不扣）。
- **收益**：被赞/被下载 → 「我的上传」手动结算（单条封顶 40 币）；每日上传限 3 条。
- **失败表现**：已配置后端但请求失败时，市场列表显示「加载失败」空态，**不回退 Mock 假数据**
  （避免用户误以为是真实内容）。未配置后端时才使用 Mock。

---

## 七、合规与署名

- 版权行、协议标题、43 个文件版权头统一使用品牌名 `AI幻舞`。
- 面向用户的一切署名只用品牌名，不使用个人真实姓名。
- 合规文案（免责声明 / 开源许可 / 用户协议 / 隐私政策）全部收敛在 `domain/LegalTexts.kt`，
  界面与网页共用同一份文本，避免两处漂移。

---

## 八、关键经验教训（踩坑）

- **坐标空间一致性**：ML Kit 输出坐标在送入 bitmap 自身空间，rotation 仅内部用；物理正立帧下游才正立。
- **CameraX 水平镜像**：Preview 走 GPU，View 级 `scaleX=-1` 不可靠 → ImageAnalysis + `lockCanvas()` 像素级镜像。
- **Bitmap.createBitmap 返回源对象**：单位矩阵时返回 src，后台 recycle 后主线程绘制崩溃 → 判 `result === src` 复制。
- **双检测器隔离**：教师帧用独立 SINGLE_IMAGE_MODE 检测器，避免污染摄像头流时序。
- **双布局必须同步**：`layout/` 与 `layout-land/` 分开维护，新增控件只改一份会导致另一方向
  `findViewById` 返回 null → NPE 崩溃。可选控件一律用可空类型 + 安全调用，不要 `lateinit` 强取。
- **Gradle Windows 锁**：`fileHashes.lock` / `checksums cache` 拒绝访问 → 构建前 `taskkill java`
  + `--no-daemon` + 全新 `--project-cache-dir`。
- **release lintVital 失败**（旧 Kotlin 插件读新版 stdlib 元数据）→ `lintOptions { checkReleaseBuilds false }`。
- **ML Kit 与 API 30**：`androidx.work` 锁 2.6.0 + 显式 `lifecycle-process:2.3.1`，否则闪退。

---

## 九、后续规划

1. 排行榜、每日任务、动作纠错详情。
2. 姿态模型内置离线，去除首次运行下载。
3. 更细腻的跟拍延迟补偿。
4. 按 ABI 拆分 APK 瘦身。
5. 适配 Android 12+ 分区存储与前台服务变更。
