> ⚠️ **文档状态（2026-08-16 标注）**：本文档为**历史设计稿**（初版规划，2026-08-09），且所描述的
> **「分段跳（SEGMENTED）模式当前已禁用**——代码全部保留未删（`TeachingActivity` 的 SEGMENTED 分支、
> `TeachingController`、`Segmenter`、`updateFrameSegmented` 等），但已从 `ModePreference.nextMode`/`getMode`
> 的切换循环与默认读取中移除。恢复只需放开 `nextMode`/`getMode`。** 当前仅 `DEBUG`/`FOLLOW` 两态可用。
> 其余架构现状以 `PROJECT_OVERVIEW.md`（最新版）为准：教师骨架改为播放时实时检测；坐标流水线保留
> `transpose` 开关（用户=true/教师=false）。设计意图仍有参考价值，请勿据此直接改代码。

# 分段式舞蹈教学功能 — 设计方案（v3）

> 状态：待审批 | 日期：2026-08-09 | 版本：v3.3（新增视频缓存+视频库历史记录）

---

## 一、需求概述

用户选择舞蹈视频后，APP 将整段舞蹈自动拆解为若干"原子动作"（子段落），然后**一段一段地带用户跟跳**：

1. 倒计时 3 秒后播放当前段老师视频（用户跟着跳）
2. 到段末尾时，暂停 5 秒检测用户姿态与老师末尾帧是否匹配（带倒计时进度条）
3. 匹配通过 → "很好！" → 自动播放下一段
4. 匹配不通过 → "再来一次" → 重新播放当前段 → 再次检测
5. 用户可手动"跳过此段"或"重看此段"
6. 所有段完成 → "本轮完成！" → 2 秒后回到第一段，**无限循环**
7. 只要不退出 APP，就一直循环

---

## 二、动作拆解算法

### 2.1 核心思路：运动能量谷检测（自适应阈值）

舞蹈动作的天然边界是"运动停顿点"——身体从动→静→动的转折处。通过计算每帧的**运动能量**（所有关键点的位移总量），能量曲线的**局部最小值**就是动作边界。

v3 改进：将固定阈值 0.20 改为**自适应阈值**——取能量曲线的 30 分位数作为候选线，适配不同节奏的舞种。

### 2.2 算法步骤

```
输入：PoseSequence（已离线提取的骨架帧序列，10fps，每100ms一帧）

第1步：计算每帧运动能量
  for i in 1..N:
    E(i) = Σ_k [ (x_k(i) - x_k(i-1))² + (y_k(i) - y_k(i-1))² ]
    // k 遍历所有关键点，坐标先经 canonical() 归一化

第2步：平滑
  E_smooth(i) = E(i-2..i+2) 的均值  // 5帧窗口

第3步：归一化
  E_norm(i) = E_smooth(i) / max(E_smooth)

第4步：自适应阈值
  threshold = percentile(E_norm, 30)  // 取30分位数，适配快/慢节奏
  // 快节奏舞蹈整体能量高 → 阈值自动提高
  // 慢节奏舞蹈整体能量低 → 阈值自动降低
  // 保底范围 [0.10, 0.35]

第5步：找候选分割点
  帧 i 是候选点，当且仅当：
    - E_norm(i) < threshold  （相对静止）
    - 在 ±3 帧窗口内是局部最小值

第6步：约束过滤
  - 最小段长 800ms（8帧）：相邻候选点间隔 < 800ms 则合并，保留能量更低的一个
  - 最大段长 3000ms（30帧）：超过则在该段内能量最低点强制分割
  - 视频首尾固定为分割点

第7步：计算每段难度
  difficulty(j) = avg(E_norm) within segment j
  → 标签：< 0.1 简单 / 0.1~0.3 中等 / > 0.3 较难

输出：List<DanceSegment>
```

### 2.3 边界情况处理

| 情况 | 处理 |
|------|------|
| 视频时长 < 3 秒 | 不拆解，整段作为一个原子动作 |
| 某些帧未检测到人体 | 运动能量用前后最近的有效帧线性插值 |
| 拆解后只有 1 段 | 正常处理，整个舞蹈一次性跟跳 |
| 帧间隔不均匀 | 用时间差加权计算运动能量 |
| 拆解后段数 > 30 | 警告"舞蹈较复杂"，但仍正常处理 |

---

## 三、数据模型

### 3.1 新增数据类

```kotlin
/** 一个原子动作段 */
data class DanceSegment(
    val index: Int,            // 第几段（0-based）
    val startMs: Long,         // 起始时间（毫秒）
    val endMs: Long,           // 结束时间（毫秒）
    val startFrame: PoseFrame, // 起始帧骨架
    val endFrame: PoseFrame,   // 末尾帧骨架（用户需要匹配的目标姿态）
    val difficulty: Float,     // 难度 0..1
    val label: String          // "简单" / "中等" / "较难"
)

/** 拆解后的分段舞蹈 */
data class SegmentedDance(
    val sequence: PoseSequence,      // 原始骨架序列
    val segments: List<DanceSegment> // 拆解结果
)

/** 单段成绩记录 */
data class SegmentScore(
    val segmentIndex: Int,    // 段序号
    val score: Float,         // 得分 0..100
    val retries: Int,         // 重试次数
    val skipped: Boolean      // 是否跳过
)
```

### 3.2 新增拆解器

```kotlin
object Segmenter {
    fun segment(seq: PoseSequence): SegmentedDance
}
```

### 3.3 视频缓存与视频库数据结构

```kotlin
/** 视频库中的一条记录（持久化到本地 JSON） */
data class VideoLibraryEntry(
    val id: String,              // 缓存ID = MD5(uri + fileSize + lastModified)
    val displayName: String,     // 显示名称（文件名去掉扩展名）
    val uri: String,             // 视频 URI（content:// 或 file://）
    val fileSize: Long,          // 文件大小（bytes，用于校验缓存有效性）
    val lastModified: Long,      // 文件最后修改时间（用于校验）
    val durationMs: Long,        // 视频时长
    val segmentCount: Int,       // 拆解出的段数
    val difficulty: String,      // 整体难度标签（按平均能量）
    val thumbnailPath: String?,  // 缩略图路径（本地缓存）
    val lastPlayedAt: Long,      // 最后一次练习的时间戳
    val totalRounds: Int,        // 累计练习轮次
    val bestScore: Float,        // 历史最高平均分（0..100，-1表示未完成过）
    val cacheDir: String         // 缓存目录路径（相对于 app cache dir）
)

/** 整个视频库 */
data class VideoLibrary(
    val entries: MutableList<VideoLibraryEntry>  // 按最后播放时间降序
)
```

### 3.4 缓存目录结构

```
app cache dir/
└── video_cache/
    ├── library.json                    ← 视频库索引（VideoLibrary 序列化）
    │
    ├── <cacheId>/                      ← 每个视频一个目录（cacheId = MD5(uri+size+mtime)）
    │   ├── pose_sequence.json          ← PoseExtractor 输出的骨架帧序列
    │   ├── segments.json               ← Segmenter 输出的分段结果
    │   ├── metadata.json               ← VideoLibraryEntry 的本地副本
    │   └── thumbnail.jpg               ← 视频缩略图（首帧或 MediaExtractor 提取）
    │
    ├── <cacheId2>/
    │   ├── pose_sequence.json
    │   ├── segments.json
    │   └── ...
    └── ...
```

### 3.5 缓存管理器

```kotlin
object VideoCacheManager {

    /** 计算视频的缓存ID */
    fun computeCacheId(uri: Uri, fileSize: Long, lastModified: Long): String =
        md5("$uri|$fileSize|$lastModified")

    /** 检查缓存是否存在且有效 */
    fun isCached(cacheId: String): Boolean

    /** 加载缓存的骨架序列 + 分段结果 */
    fun loadCache(cacheId: String): Pair<PoseSequence, SegmentedDance>?

    /** 保存骨架序列 + 分段结果到缓存 */
    fun saveCache(cacheId: String, sequence: PoseSequence, segmented: SegmentedDance)

    /** 加载整个视频库索引 */
    fun loadLibrary(context: Context): VideoLibrary

    /** 保存视频库索引 */
    fun saveLibrary(context: Context, library: VideoLibrary)

    /** 添加/更新一条视频库记录（选了新视频或重新练习时调用） */
    fun upsertEntry(context: Context, entry: VideoLibraryEntry)

    /** 删除一条视频库记录 + 清理缓存目录 */
    fun deleteEntry(context: Context, cacheId: String)

    /** 获取视频缩略图路径（不存在则从视频提取并缓存） */
    fun getThumbnail(context: Context, uri: Uri, cacheId: String): String?
}
```

---

## 四、教学模式状态机

### 4.1 状态定义

```
IDLE          初始空闲，等待用户选择视频
ANALYZING     正在预处理视频 + 拆解动作（显示进度）
              ← 先查缓存：有缓存则直接加载（< 1秒），无缓存才跑 PoseExtractor + Segmenter
COUNTDOWN     倒计时 3 秒，准备开始播放当前段
WATCHING      播放当前段视频（老师示范），用户跟着跳
CHECKING      视频暂停在段末尾帧，5秒窗口检测匹配（带倒计时进度条）
MATCHED       匹配成功，展示"很好！✓"，1秒后进入下一段
REPLAY        匹配失败，提示"再来一次"，1.5秒后重新进入 COUNTDOWN
SKIPPED       用户手动跳过，0.5秒后进入下一段
COMPLETED     所有段完成一轮，展示总评，2秒后回到第0段
```

### 4.2 状态流转

```
IDLE
  │ 用户选视频（新视频 或 从视频库选择历史视频）
  ▼
ANALYZING
  │
  ├─ 有缓存？（cacheId 匹配）
  │   ├─ 是 → 直接加载 pose_sequence.json + segments.json（< 1秒）
  │   │       状态栏显示"正在加载缓存…"
  │   │       → 跳到 COUNTDOWN
  │   │
  │   └─ 否 → 跑 PoseExtractor.preprocessVideo（显示"已处理 X/Y 帧"）
  │            → 跑 Segmenter.segment（显示"正在拆解动作…"）
  │            → 保存到缓存目录 + 更新视频库索引
  │            → 跳到 COUNTDOWN
  │
  ▼
COUNTDOWN（"3, 2, 1, 开始！"）
  │ 倒计时结束
  ▼
WATCHING（播放第 N 段，用户跟跳）
  │ 视频播放到段末尾
  ▼
CHECKING（暂停 5 秒，带倒计时进度条，持续检测匹配）
  │
  ├─ 相似度 ≥ 阈值，持续 ≥ 5帧（~170ms）
  │    ▼
  │  MATCHED（"很好！✓"，停留 1 秒）
  │    │ 还有下一段？
  │    ├─ 是 → COUNTDOWN（下一段倒计时）
  │    └─ 否 → COMPLETED
  │
  ├─ 用户点击"跳过此段"
  │    ▼
  │  SKIPPED（"已跳过"，0.5 秒）
  │    │ 还有下一段？
  │    ├─ 是 → COUNTDOWN（下一段倒计时）
  │    └─ 否 → COMPLETED
  │
  └─ 5 秒超时，未达到匹配
       ▼
     REPLAY（"再来一次！第 X 次"，1.5 秒）
       ▼
     COUNTDOWN（重新倒计时 → WATCHING 当前段）

COMPLETED（"第 Y 轮完成！平均分 XX%"，2 秒）
  │ 自动回到第 0 段
  ▼
COUNTDOWN → WATCHING（第 0 段，新一轮）
```

### 4.3 COUNTDOWN 阶段

```
- 显示大字倒计时："3" → "2" → "1" → "开始！"（各 1 秒）
- 老师视频暂停在 segment.startMs（用户可以先看起始姿势）
- 摄像头预览正常显示
- 倒计时结束后 → WATCHING
- 如果是 REPLAY 后的倒计时，额外显示"再来一次"
```

### 4.4 CHECKING 阶段匹配判定

```
每 50ms tick 一次：
  1. 取用户当前骨架 latestUser
  2. 取当前段末尾帧 endFrame.landmarks
  3. ComparisonEngine.compare(endFrame.landmarks, latestUser, false, false)
  4. 记录本次 CHECKING 窗口内的峰值相似度 peakScore
  5. 如果 result.score >= matchThreshold：
       consecutiveMatchFrames++
       如果 consecutiveMatchFrames >= 5（~250ms持续匹配）：
         → 状态切换到 MATCHED
  6. 否则：
       consecutiveMatchFrames = 0
  7. 如果 CHECKING 持续时间 ≥ 5000ms：
       → 状态切换到 REPLAY

UI 反馈：
  - 顶部显示"保持这个姿势！" + 实时相似度
  - 底部显示 5 秒倒计时进度条（从满到空）
  - 动作提示保持显示
```

### 4.5 运动验证（防"站着不动"）

```
WATCHING 阶段持续记录用户的运动能量 userMotionEnergy：
  - 每帧记录用户 landmark 的帧间位移
  - WATCHING 结束时计算总位移

进入 CHECKING 前：
  if (userMotionEnergy < MIN_MOTION_THRESHOLD) {
      // 用户几乎没动，可能是没在跟跳
      顶部显示"请跟着老师一起跳哦！"
      → 直接进入 REPLAY 而非 CHECKING
  } else {
      → 正常进入 CHECKING
  }

MIN_MOTION_THRESHOLD = 0.5（归一化坐标空间下的总位移）
```

### 4.6 循环播放逻辑

```
当前段 index 到达 segments.lastIndex 且 MATCHED/SKIPPED 时：
  - 进入 COMPLETED 状态
  - 显示"第 Y 轮完成！平均分 XX%"
  - 显示各段得分简要（✓/✗/跳过 + 分数）
  - 2 秒后：
    - 重置 index = 0
    - round++（轮次累计）
    - 清空本轮成绩列表
    - 进入 COUNTDOWN（第 0 段）
  - 只要不退出 APP / 不选新视频，就无限循环
```

### 4.7 REPLAY 重试逻辑

```
进入 REPLAY 时：
  - retryCount++（记录当前段重试次数）
  - 显示"再来一次！第 X 次尝试"
  - 第 3 次起额外提示"跳过此段也可以继续哦"
  - 1.5 秒后自动进入 COUNTDOWN：
    - VideoView.seekTo(segment.startMs)
    - 倒计时后重新播放
  - 重试次数无上限

重试对分数的影响：
  - 段得分 = peakScore × (1 - 0.05 × retryCount)
  - 最低不低于 peakScore × 0.5
  - 即：1次过=满分，每重试1次扣5%，最多扣到一半
```

---

## 五、评分体系

### 5.1 段得分计算

```
段得分 = CHECKING 窗口内的 peakScore（峰值相似度）× 重试折扣

示例：
  - 第1次就通过，peakScore=85% → 段得分 = 85 × (1-0) = 85
  - 第3次通过，peakScore=80% → 段得分 = 80 × (1-0.15) = 68
  - 跳过 → 不计入段得分
```

### 5.2 轮次总评

```
轮次平均分 = sum(各段得分) / count(未跳过的段)

  - 跳过的段从分母中排除，不拉低平均分
  - 如果所有段都跳过 → 平均分 = 0，提示"试着跟跳看看吧！"

轮次总评显示：
  "第 Y 轮完成！"
  "平均分：XX%"
  "通过 X 段 / 跳过 Y 段"
  各段简要列表：[✓85] [✓72] [跳过] [✓90] [✗→✓68] ...
```

---

## 六、UI 变更

### 6.1 整体布局

**取消底部栏，所有控制按钮集中到左侧半透明侧边栏。**

```
┌──────┬──────────────────────────────────────────────────┐
│ 第 3/12 段 · 中等难度 · 第2轮   相似度 75%  注意左臂     │ ← 顶部栏（跨全宽）
├──────┼──────────────────────────┬───────────────────────┤
│      │                          │                       │
│ 侧   │                          │                       │
│ 边   │   老师视频                │   摄像头预览          │
│ 栏   │   (播放/暂停在末尾帧)     │                       │
│      │                          │                       │
│ ┌──┐ │                          │                       │
│ │📹│ │                          │                       │
│ │  │ │                          │                       │
│ │历│ │                          │                       │
│ │史│ │                          │                       │
│ │视│ │  ████████░░░░ 67%       │                       │
│ │频│ │                          │                       │
│ │▼│ │                          │                       │
│ │🗺│ │                          │                       │
│ │💃│ │                          │                       │
│ │🎵│ │                          │                       │
│ └──┘ │                          │                       │
│ ──── │                          │                       │
│ 阈值 │                          │                       │
│ ─●─ │                          │                       │
│ 70%  │                          │                       │
│ ──── │                          │                       │
│ ⏮上一│                          │                       │
│ 🔄重看│                          │                       │
│ ⏭跳过│                          │                       │
│ ──── │                          │                       │
│ ↺  ↻ │                          │                       │
│ 镜像✓│                          │                       │
│ 🔊   │                          │                       │
│      │                          │                       │
└──────┴──────────────────────────┴───────────────────────┘

侧边栏放大示意（竖排，半透明背景，可滚动）：
  ┌────────────┐
  │  📹 选视频  │  ← 点击打开系统视频选择器
  ├────────────┤
  │ 📋 历史视频 ▼│  ← 可展开/收起的折叠区
  │────────────│
  │ 🗺 地方舞   │  ← 历史视频列表项（缩略图+名称+段数+最高分）
  │ 12段 · 85分 │     点击直接加载（有缓存秒加载）
  │────────────│
  │ 💃 街舞基础 │
  │  8段 · 72分 │
  │────────────│
  │ 🎵 华尔兹   │
  │ 15段 · 未完成│
  ├────────────┤
  │ 阈值       │
  │ ──●─────── │  ← SeekBar
  │  70%       │
  ├────────────┤
  │ ⏮ 上一段   │  ← 仅 WATCHING/CHECKING 可用
  │ 🔄 重看    │
  │ ⏭ 跳过    │
  ├────────────┤
  │ ↺  ↻       │  ← 旋转
  │ 镜像✓      │  ← 镜像开关
  │  🔊        │  ← 静音开关
  └────────────┘

COUNTDOWN 状态覆盖层（叠在老师视频上）：
  ┌──────────────────────────┐
  │                          │
  │         ┌─────┐          │
  │         │  3  │          │
  │         └─────┘          │
  │    （起始姿势预览）        │
  │                          │
  └──────────────────────────┘

CHECKING 状态倒计时条（叠在老师视频底部）：
  ████████████████████░░░░░░░░  3.2s  保持这个姿势！
```

**侧边栏实现要点：**
- 使用 `ConstraintLayout` 或 `LinearLayout`（水平）作为根布局，侧边栏为第一个子节点，占固定宽度 ~96dp
- 侧边栏背景半透明：`android:background="#CC1A1A2E"`（深色半透明，alpha=0xCC 约80%不透明）
- 侧边栏内容竖排排列，分为 5 组（用分隔线或间距区分）：
  1. 选舞蹈视频（打开系统视频选择器）
  2. **历史视频列表**（可展开/收起的折叠区，显示视频库中的历史视频，点击直接加载缓存）
  3. 匹配阈值滑块 + 数值
  4. 教学操作按钮（上一段/重看/跳过）
  5. 摄像头控制（旋转/镜像/静音）
- 侧边栏整体可垂直滚动（`ScrollView` 包裹），因为视频库条目多时可能超出屏幕高度
- 段进度条悬浮在老师视频底部（FrameLayout 层叠）
- COUNTDOWN 倒计时文字悬浮在老师视频中央
- CHECKING 倒计时条悬浮在老师视频底部
- **不再有底部栏**，底部空间完全留给视频和摄像头

### 6.2 顶部栏变化

- 新增："第 X/N 段 · 难度标签 · 第Y轮"
- 新增：段进度条（当前位置在整个舞蹈中的占比）
- 相似度和动作提示保持不变

### 6.3 侧边栏 — 教学操作按钮

操作按钮放在左侧边栏的第 3 组，不悬浮在视频上，也不在底部单独开栏：

- **上一段** — 回到上一段重新跳（WATCHING/CHECKING 阶段可用，其他状态 disabled）
- **重看** — 重新播放当前段（WATCHING/CHECKING 阶段可用，不重置 retryCount）
- **跳过此段** — 跳到下一段（WATCHING/CHECKING 阶段可用）

**侧边栏整体 XML 布局：**
```xml
<!-- 根布局：水平 LinearLayout -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="horizontal">

    <!-- ═══ 左侧边栏（所有控制按钮 + 历史视频列表） ═══ -->
    <ScrollView
        android:layout_width="96dp"
        android:layout_height="match_parent"
        android:background="#CC1A1A2E">

        <LinearLayout
            android:id="@+id/sidebar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:gravity="center_horizontal"
            android:paddingTop="8dp"
            android:paddingBottom="8dp">

            <!-- 第1组：选舞蹈视频 -->
            <ImageButton
                android:id="@+id/btnPickVideo"
                android:layout_width="56dp"
                android:layout_height="56dp"
                android:src="@drawable/ic_video"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="选舞蹈视频" />
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="选视频"
                android:textColor="#CCCCCC"
                android:textSize="10sp" />

            <!-- 分隔线 -->
            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginTop="8dp"
                android:layout_marginBottom="8dp"
                android:background="#33FFFFFF" />

            <!-- 第2组：历史视频列表（可展开/收起） -->
            <LinearLayout
                android:id="@+id/historyHeader"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center"
                android:padding="4dp">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="历史视频"
                    android:textColor="#CCCCCC"
                    android:textSize="10sp" />
                <ImageView
                    android:id="@+id/ivHistoryArrow"
                    android:layout_width="16dp"
                    android:layout_height="16dp"
                    android:src="@drawable/ic_arrow_down" />
            </LinearLayout>

            <!-- 历史视频列表容器（代码动态填充 VideoLibraryEntry 条目） -->
            <LinearLayout
                android:id="@+id/historyListContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:visibility="gone">

                <!-- 每个条目模板（代码动态生成）：
                ┌──────────────┐
                │ [缩略图]      │
                │ 地方舞.mp4   │
                │ 12段 · 85分  │
                └──────────────┘ -->
                <!-- 示例条目 -->
                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:gravity="center"
                    android:padding="4dp"
                    android:background="?attr/selectableItemBackground">
                    <ImageView
                        android:layout_width="56dp"
                        android:layout_height="32dp"
                        android:scaleType="centerCrop"
                        android:src="@drawable/ic_video_placeholder" />
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="地方舞"
                        android:textColor="#FFFFFF"
                        android:textSize="9sp"
                        android:maxLines="1"
                        android:ellipsize="end" />
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="12段·85分"
                        android:textColor="#888888"
                        android:textSize="8sp" />
                </LinearLayout>
            </LinearLayout>

            <!-- 分隔线 -->
            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginTop="8dp"
                android:layout_marginBottom="8dp"
                android:background="#33FFFFFF" />

            <!-- 第3组：匹配阈值 -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="匹配阈值"
                android:textColor="#CCCCCC"
                android:textSize="10sp" />
            <SeekBar
                android:id="@+id/seekThreshold"
                android:layout_width="80dp"
                android:layout_height="wrap_content"
                android:max="50"
                android:progress="30" />
            <TextView
                android:id="@+id/tvThresholdValue"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="70%"
                android:textColor="#FFFFFF"
                android:textSize="12sp" />

            <!-- 分隔线 -->
            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginTop="8dp"
                android:layout_marginBottom="8dp"
                android:background="#33FFFFFF" />

            <!-- 第4组：教学操作按钮 -->
            <Button
                android:id="@+id/btnPrevSegment"
                android:layout_width="80dp"
                android:layout_height="36dp"
                android:text="上一段"
                android:textSize="11sp"
                android:enabled="false" />
            <Button
                android:id="@+id/btnReplay"
                android:layout_width="80dp"
                android:layout_height="36dp"
                android:layout_marginTop="4dp"
                android:text="重看"
                android:textSize="11sp"
                android:enabled="false" />
            <Button
                android:id="@+id/btnSkipSegment"
                android:layout_width="80dp"
                android:layout_height="36dp"
                android:layout_marginTop="4dp"
                android:text="跳过"
                android:textSize="11sp"
                android:enabled="false" />

            <!-- 分隔线 -->
            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginTop="8dp"
                android:layout_marginBottom="8dp"
                android:background="#33FFFFFF" />

            <!-- 第5组：摄像头控制 -->
            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal">
                <ImageButton
                    android:id="@+id/btnRotateLeft"
                    android:layout_width="36dp"
                    android:layout_height="36dp"
                    android:src="@drawable/ic_rotate_left"
                    android:background="?attr/selectableItemBackgroundBorderless" />
                <ImageButton
                    android:id="@+id/btnRotateRight"
                    android:layout_width="36dp"
                    android:layout_height="36dp"
                    android:src="@drawable/ic_rotate_right"
                    android:background="?attr/selectableItemBackgroundBorderless" />
            </LinearLayout>
            <Button
                android:id="@+id/btnFlip"
                android:layout_width="80dp"
                android:layout_height="32dp"
                android:layout_marginTop="4dp"
                android:text="镜像✓"
                android:textSize="11sp" />
            <ImageButton
                android:id="@+id/btnMute"
                android:layout_width="36dp"
                android:layout_height="36dp"
                android:layout_marginTop="4dp"
                android:src="@drawable/ic_volume_on"
                android:background="?attr/selectableItemBackgroundBorderless" />
        </LinearLayout>
    </ScrollView>

    <!-- ═══ 老师视频区（FrameLayout 层叠） ═══ -->
    <FrameLayout
        android:id="@+id/teacherVideoContainer"
        android:layout_width="0dp"
        android:layout_height="match_parent"
        android:layout_weight="1">

        <com.google.android.exoplayer2.ui.PlayerView
            android:id="@+id/playerView"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

        <!-- 段进度条（悬浮在视频底部） -->
        <ProgressBar
            android:id="@+id/segmentProgress"
            android:layout_width="match_parent"
            android:layout_height="4dp"
            android:layout_gravity="bottom"
            style="?android:attr/progressBarStyleHorizontal"
            android:progressTint="#FF6200EE"
            android:backgroundTint="#44000000" />

        <!-- CHECKING 倒计时条（悬浮在视频底部上方） -->
        <LinearLayout
            android:id="@+id/checkingBar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom"
            android:layout_marginBottom="8dp"
            android:orientation="horizontal"
            android:gravity="center"
            android:background="#99000000"
            android:padding="4dp"
            android:visibility="gone">
            <ProgressBar
                android:id="@+id/checkingCountdown"
                android:layout_width="0dp"
                android:layout_height="6dp"
                android:layout_weight="1"
                style="?android:attr/progressBarStyleHorizontal" />
            <TextView
                android:id="@+id/tvCheckingTime"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="#FFFFFF"
                android:textSize="12sp"
                android:layout_marginStart="8dp" />
        </LinearLayout>

        <!-- COUNTDOWN 倒计时覆盖层 -->
        <TextView
            android:id="@+id/countdownOverlay"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:textSize="120sp"
            android:textColor="#FFFFFF"
            android:shadowColor="#99000000"
            android:shadowRadius="8"
            android:visibility="gone" />
    </FrameLayout>

    <!-- ═══ 摄像头预览区 ═══ -->
    <TextureView
        android:id="@+id/cameraPreview"
        android:layout_width="0dp"
        android:layout_height="match_parent"
        android:layout_weight="1" />
</LinearLayout>
```

**按钮启用/禁用逻辑：**
| 状态 | 上一段 | 重看 | 跳过 |
|------|--------|------|------|
| IDLE / ANALYZING | disabled | disabled | disabled |
| COUNTDOWN | disabled | disabled | disabled |
| WATCHING | enabled (index>0时) | enabled | enabled |
| CHECKING | enabled (index>0时) | enabled | enabled |
| MATCHED / REPLAY / SKIPPED / COMPLETED | disabled | disabled | disabled |

### 6.4 底部栏 — 取消

**不再有底部栏。** 原底部栏的所有内容已移入左侧边栏：
- "选舞蹈视频"按钮 → 侧边栏第 1 组
- 匹配阈值 SeekBar → 侧边栏第 3 组
- "示例动作"按钮 → 删除

### 6.5 历史视频列表（侧边栏第 2 组）

侧边栏新增可展开/收起的历史视频折叠区：

**交互行为：**
- 点击"历史视频 ▼"标题 → 展开/收起视频列表
- 列表按 `lastPlayedAt` 降序排列（最近播放的在最上面）
- 每个条目显示：缩略图（56x32dp）+ 视频名称 + "X段·YY分"（段数+最高分）
- 点击条目 → 直接加载该视频（有缓存则秒加载，无缓存则重新分析）
- 长按条目 → 弹出删除确认对话框（"从视频库中删除？缓存也会清除"）
- 列表为空时显示"暂无历史视频"
- 列表最多显示 50 条，超出按时间截断

**条目状态标记：**
- 正在播放的视频 → 高亮背景 + 左侧竖线标记
- 未完成过一轮（bestScore < 0）→ 显示"未完成"替代分数

**代码动态填充逻辑：**
```kotlin
fun refreshHistoryList() {
    val library = VideoCacheManager.loadLibrary(this)
    historyListContainer.removeAllViews()

    if (library.entries.isEmpty()) {
        // 显示"暂无历史视频"
        addEmptyView()
        return
    }

    for (entry in library.entries.take(50)) {
        val itemView = createHistoryItem(entry)  // 缩略图+名称+段数分数
        itemView.setOnClickListener {
            loadVideoFromLibrary(entry)  // 缓存优先加载
        }
        itemView.setOnLongClickListener {
            showDeleteDialog(entry)
            true
        }
        historyListContainer.addView(itemView)
    }
}
```

### 6.6 摄像头控制区 — 移入侧边栏

摄像头控制按钮从原来的摄像头预览区底部移到侧边栏第 5 组：
- 镜像按钮**默认选中**（`mirror = true`，按钮文字"镜像✓"）
- 旋转按钮（↺↻）和镜像按钮保持现有行为，位置移到侧边栏
- 新增静音按钮 🔊/🔇，控制老师视频音频开关

### 6.7 新增代码文件

| 文件 | 职责 |
|------|------|
| `domain/Segmenter.kt` | 动作拆解算法（自适应阈值） |
| `domain/DanceSegment.kt` | DanceSegment + SegmentedDance + SegmentScore 数据类 |
| `domain/VideoLibraryEntry.kt` | VideoLibraryEntry + VideoLibrary 数据类 |
| `data/VideoCacheManager.kt` | 视频缓存读写 + 视频库索引管理 + 缩略图提取 |
| `ui/TeachingController.kt` | 教学状态机，管理所有状态流转、评分、循环 |

### 6.8 修改的现有文件

| 文件 | 改动 |
|------|------|
| `MainActivity.kt` | 集成 TeachingController，删除示例动作，连接所有新按钮和滑块；选视频后优先查缓存；刷新历史视频列表 |
| `activity_main.xml` | 删除示例动作按钮和底部栏，新增左侧边栏（5组：选视频/历史视频列表/阈值/教学操作/摄像头控制），老师视频区改 FrameLayout 层叠 |
| `CameraManager.kt` | `mirror` 默认值改为 `true` |
| `PoseSequenceStore.kt` | 增加 SegmentedDance 的序列化（复用 JSON 格式，写到 video_cache 目录） |
| `PoseExtractor.kt` | 预处理增加 progress callback；预处理完成后调用 VideoCacheManager.saveCache |
| `build.gradle` | 新增 ExoPlayer 依赖（替代 VideoView，精确 seekTo） |

---

## 七、与现有代码的集成

### 7.1 不受影响的部分

- `PoseExtractor.kt` — 核心检测逻辑不改，增加 progress callback 和缓存保存调用
- `PoseUtils.kt` — 完全不改
- `ComparisonEngine.kt` — 完全不改

### 7.2 视频缓存与视频库集成

**选视频时的完整流程（缓存优先）：**

```kotlin
fun onVideoSelected(uri: Uri) {
    // 1. 获取视频文件信息
    val cursor = contentResolver.query(uri, null, null, null, null)
    val fileSize = cursor.getLong(FILE_SIZE_INDEX)
    val lastModified = cursor.getLong(DATE_MODIFIED_INDEX)
    val duration = cursor.getLong(DURATION_INDEX)
    val displayName = cursor.getString(DISPLAY_NAME_INDEX)
    cursor.close()

    // 2. 计算缓存 ID
    val cacheId = VideoCacheManager.computeCacheId(uri, fileSize, lastModified)

    // 3. 检查缓存
    if (VideoCacheManager.isCached(cacheId)) {
        // 缓存命中 → 秒加载
        statusText.text = "正在加载缓存…"
        coroutineScope.launch(Dispatchers.IO) {
            val (sequence, segmented) = VideoCacheManager.loadCache(cacheId)!!
            withContext(Dispatchers.Main) {
                currentSequence = sequence
                currentSegmentedDance = segmented
                teachingController.startWith(segmented)
                // 更新 lastPlayedAt
                updateLibraryEntry(cacheId, displayName, uri, fileSize, lastModified, duration, segmented)
                refreshHistoryList()
            }
        }
    } else {
        // 缓存未命中 → 完整分析流程
        statusText.text = "正在分析舞蹈视频…"
        coroutineScope.launch(Dispatchers.IO) {
            // 3a. PoseExtractor 抽帧
            val sequence = extractor.preprocessVideo(this, uri, displayName) { current, total ->
                runOnUiThread {
                    statusText.text = "正在分析… 已处理 $current/$total 帧"
                    progressBar.progress = (current.toFloat() / total * 100).toInt()
                }
            }

            // 3b. Segmenter 拆解
            runOnUiThread { statusText.text = "正在拆解动作…" }
            val segmented = Segmenter.segment(sequence)

            // 3c. 保存到缓存
            VideoCacheManager.saveCache(cacheId, sequence, segmented)

            // 3d. 提取缩略图
            val thumbPath = VideoCacheManager.getThumbnail(this, uri, cacheId)

            // 3e. 更新视频库索引
            val entry = VideoLibraryEntry(
                id = cacheId,
                displayName = displayName.substringBeforeLast("."),
                uri = uri.toString(),
                fileSize = fileSize,
                lastModified = lastModified,
                durationMs = duration,
                segmentCount = segmented.segments.size,
                difficulty = computeOverallDifficulty(segmented),
                thumbnailPath = thumbPath,
                lastPlayedAt = System.currentTimeMillis(),
                totalRounds = 0,
                bestScore = -1f,
                cacheDir = "video_cache/$cacheId"
            )
            VideoCacheManager.upsertEntry(this, entry)

            withContext(Dispatchers.Main) {
                currentSequence = sequence
                currentSegmentedDance = segmented
                teachingController.startWith(segmented)
                refreshHistoryList()
            }
        }
    }
}
```

**缓存有效性校验：**
- 缓存 ID = `MD5(uri + fileSize + lastModified)`
- 如果用户修改了视频文件（大小或修改时间变化）→ cacheId 不同 → 自动重新分析
- 如果用户删除了视频源文件 → 从视频库列表中点击时检测 URI 是否可访问，不可访问则提示"视频文件已删除"，可手动从库中删除

**缓存清理策略：**
- 用户长按历史视频条目 → 手动删除（清理缓存目录 + 从 library.json 移除）
- APP 设置中可加"清理所有缓存"按钮（本次不实现，列入未来路线图）
- 不做自动 LRU 淘汰（用户视频数量通常不大，手动管理即可）

**轮次完成时更新视频库：**
```kotlin
fun onRoundCompleted(averageScore: Float) {
    val entry = currentLibraryEntry!!
    entry.totalRounds++
    entry.lastPlayedAt = System.currentTimeMillis()
    if (averageScore > entry.bestScore) {
        entry.bestScore = averageScore
    }
    VideoCacheManager.upsertEntry(this, entry)
    refreshHistoryList()
}
```

### 7.3 镜像参数确认

**结论：`compare(teacher, user, false, false)` 正确，无需修改。**

原因分析：
- CameraManager 的 `createDisplayBitmap` 中的 `postScale(-1, 1)` 仅影响显示像素
- ML Kit 姿态检测用的是 `rawBitmap`（原始未翻转 bitmap）+ `rotationDegrees`
- landmark 坐标基于原始图像，不受显示镜像影响
- 老师视频中的老师面朝摄像头，用户也面朝摄像头 → 双方坐标空间一致
- `canonical(landmarks, false)` 不做镜像翻转 → 正确比较

如果未来出现老师背对摄像头的视频，可加一个"老师朝向"设置项，切到 `compare(teacher, user, true, false)`。

### 7.4 VideoView → ExoPlayer 迁移

**问题：** Android `VideoView.seekTo()` 精度差（偏差 100~300ms），段边界对不准。

**方案：** 替换为 ExoPlayer，支持关键帧精确 seek。

```kotlin
// build.gradle 新增
implementation 'com.google.android.exoplayer:exoplayer-core:2.19.1'
implementation 'com.google.android.exoplayer:exoplayer-ui:2.19.1'

// 使用
val player = ExoPlayer.Builder(context).build()
player.setMediaItem(MediaItem.fromUri(uri))
player.prepare()
// 精确 seek 到段起始
player.seekTo(segment.startMs)
player.play()
// 播放到段末尾
if (player.currentPosition >= segment.endMs) {
    player.pause()
}
```

### 7.5 预处理进度反馈

```kotlin
// PoseExtractor.preprocessVideo 增加 progress callback
fun preprocessVideo(
    context: Context,
    uri: Uri,
    name: String,
    onProgress: (Int, Int) -> Unit  // (current, total)
): PoseSequence

// MainActivity 调用
extractor.preprocessVideo(this, uri, name) { current, total ->
    runOnUiThread {
        statusText.text = "正在分析… 已处理 $current/$total 帧"
        progressBar.progress = (current.toFloat() / total * 100).toInt()
    }
}
```

### 7.6 tick 频率调整

```
v2: 33ms tick（~30fps）
v3: 50ms tick（~20fps）

原因：
  - 30fps 下每帧都要做 compare + UI 更新，中低端手机可能掉帧
  - 20fps 足够做姿态匹配（ML Kit 本身 ~12fps）
  - CHECKING 的 5 帧连续匹配 = 250ms，仍然合理
  - WATCHING 阶段只需检测视频位置，50ms 完全够用
```

### 7.7 生命周期处理

```kotlin
// onPause: 用户切到后台（如来了电话）
override fun onPause() {
    super.onPause()
    running = false
    handler.removeCallbacks(tickRunnable)
    teachingController.pause()  // 记录当前状态
    exoPlayer.pause()
    cameraManager?.stop()
}

// onResume: 用户回到 APP
override fun onResume() {
    super.onResume()
    if (teachingController.state != IDLE) {
        // 恢复到之前的状态
        running = true
        teachingController.resume()
        handler.post(tickRunnable)
        // 如果在 WATCHING，从当前位置继续播放
        // 如果在 CHECKING，重新开始 5 秒倒计时
        // 如果在 COUNTDOWN，重新开始 3 秒倒计时
    }
}
```

### 7.8 MainActivity.kt 的 updateFrame 改动

```kotlin
private fun updateFrame() {
    val user = latestUser.get()
    when (teachingController.state) {
        IDLE, ANALYZING -> { /* 等待 */ }

        COUNTDOWN -> {
            // 倒计时由 TeachingController 内部 Handler 管理
            // updateFrame 不需要做事
        }

        WATCHING -> {
            // 1. 检测视频是否到达段末尾
            if (exoPlayer.currentPosition >= currentSegment.endMs) {
                exoPlayer.pause()
                // 2. 运动验证
                if (userMotionEnergy < MIN_MOTION) {
                    statusText = "请跟着老师一起跳哦！"
                    teachingController.transitionTo(REPLAY)
                } else {
                    teachingController.transitionTo(CHECKING)
                }
            }
            // 3. 累计用户运动能量
            accumulateUserMotion(user)
        }

        CHECKING -> {
            val target = currentSegment.endFrame.landmarks
            val result = ComparisonEngine.compare(target, user, false, false)
            peakScore = maxOf(peakScore, result.score)
            scoreText.text = "相似度 ${result.score.toInt()}%"
            hintText.text = result.hint

            if (result.score >= matchThreshold) {
                consecutiveMatch++
                if (consecutiveMatch >= 5) {
                    teachingController.transitionTo(MATCHED)
                }
            } else {
                consecutiveMatch = 0
            }

            // 倒计时由 TeachingController 管理，超时 → REPLAY
        }

        MATCHED, REPLAY, SKIPPED, COMPLETED -> {
            // 状态转换由 TeachingController 内部定时器管理
        }
    }
}
```

---

## 八、参数汇总

| 参数 | 默认值 | 可调 | 说明 |
|------|--------|------|------|
| 抽帧间隔 | 100ms (10fps) | 否 | 已有，不变 |
| 运动能量阈值 | 自适应（30分位数） | 否 | 自适应快/慢节奏，范围 [0.10, 0.35] |
| 平滑窗口 | 5帧 | 否 | 运动能量移动平均窗口 |
| 最小段长 | 800ms | 否 | 短于此的段合并 |
| 最大段长 | 3000ms | 否 | 长于此的段强制分割 |
| **匹配阈值** | **70%** | **是（SeekBar 40%~90%）** | 相似度 ≥ 阈值视为匹配 |
| 持续匹配帧数 | 5帧 (~250ms) | 否 | 连续匹配才算通过（v2 的 3 帧太短） |
| CHECKING 窗口 | 5000ms | 否 | 超时未匹配则 REPLAY |
| COUNTDOWN 时长 | 3000ms | 否 | "3, 2, 1, 开始" |
| MATCHED 停留 | 1000ms | 否 | 成功反馈展示时间 |
| REPLAY 停留 | 1500ms | 否 | "再来一次"提示时间 |
| SKIPPED 停留 | 500ms | 否 | "已跳过"提示时间 |
| COMPLETED 停留 | 2000ms | 否 | "本轮完成"展示后回到第0段 |
| tick 间隔 | 50ms (20fps) | 否 | v2 的 33ms 改为 50ms 降低性能压力 |
| 最小运动量 | 0.5 | 否 | WATCHING 期间用户运动量低于此不进入 CHECKING |
| 重试分数折扣 | 5%/次 | 否 | 每重试一次扣 5%，最多扣到 50% |

---

## 九、开发计划

| 步骤 | 内容 | 依赖 |
|------|------|------|
| 1 | 删除示例动作按钮，镜像默认开启，添加 ExoPlayer 依赖 | 无 |
| 2 | 创建 DanceSegment + SegmentScore 数据类 + Segmenter 拆解算法 | 无 |
| 3 | 创建 VideoLibraryEntry 数据类 + VideoCacheManager（缓存读写+视频库索引+缩略图提取） | 无 |
| 4 | 创建 TeachingController 状态机（含 COUNTDOWN、REPLAY、循环、评分） | 步骤2 |
| 5 | 修改 activity_main.xml：左侧边栏5组（选视频/历史视频列表/阈值/教学操作/摄像头控制），取消底部栏，老师视频区 FrameLayout 层叠 | 无 |
| 6 | 修改 MainActivity.kt：集成 TeachingController + ExoPlayer + VideoCacheManager + 历史视频列表刷新 + 缓存优先加载逻辑 | 步骤1+3+4+5 |
| 7 | 修改 PoseExtractor.kt：预处理进度 callback + 完成后调用 VideoCacheManager.saveCache | 步骤3 |
| 8 | 修改 PoseSequenceStore.kt：支持 SegmentedDance 序列化到 video_cache 目录 | 步骤2+3 |
| 9 | 生命周期处理：onPause/onResume | 步骤6 |
| 10 | 编译、安装、调测（重点测试缓存命中/未命中/历史视频加载/删除） | 步骤6+7+8+9 |

---

## 十、与 v2 的变更对比

| 项目 | v2 | v3 |
|------|----|----|
| 拆解阈值 | 固定 0.20 | **自适应（30分位数，[0.10, 0.35]）** |
| 倒计时 | 无 | **COUNTDOWN 状态，3秒"3-2-1-开始"** |
| CHECKING 倒计时显示 | 无 | **5秒倒计时进度条** |
| 连续匹配帧数 | 3帧 (~100ms) | **5帧 (~250ms)** |
| 运动验证 | 无 | **WATCHING 期间检测用户运动量，不动不进入 CHECKING** |
| 视频播放器 | VideoView | **ExoPlayer（精确 seekTo）** |
| 预处理进度 | 仅"正在分析"文字 | **"已处理 X/Y 帧" + 进度条** |
| tick 频率 | 33ms (~30fps) | **50ms (~20fps)** |
| 生命周期 | 未设计 | **onPause/onResume 完整处理** |
| 评分体系 | 未定义 | **峰值相似度 × 重试折扣，跳过不计入** |
| 重看按钮 | 无 | **"重看"按钮（不重置 retryCount）** |
| 上一段按钮 | 无 | **"上一段"按钮** |
| 静音按钮 | 无 | **老师视频音频开关** |
| 镜像参数 | 未确认 | **已确认 false,false 正确，文档说明原理** |
| 操作按钮位置 | 底部独立操作栏 | **左侧边栏第3组（与选视频/阈值/摄像头控制同栏）** |
| 摄像头控制位置 | 摄像头预览区底部 | **移入左侧边栏第5组** |
| 视频分析缓存 | 无 | **按视频指纹缓存骨架序列+分段结果，二次加载秒级** |
| 视频库/历史记录 | 无 | **侧边栏可展开历史视频列表，点击直接加载缓存** |
| 缩略图缓存 | 无 | **每个视频提取缩略图缓存到本地** |
| 手动调整拆解 | 无 | 列入未来路线图 |

---

## 十一、未来路线图（本次不实现）

1. **手动调整拆解** — 用户可合并/拆分自动生成的段
2. **慢动作播放** — 高难度段 0.5x 慢放示范
3. **摄像头设置持久化** — 旋转/镜像/静音状态保存到 SharedPreferences
4. **老师朝向设置** — 应对老师背对摄像头的视频
5. **段跳转** — 点击段进度条直接跳到某一段
6. **录音回放** — 录制用户跟跳视频供回看
7. **社交分享** — 完成一轮后分享成绩截图
8. **缓存自动清理** — LRU 淘汰策略，设置缓存上限
9. **视频库搜索/分类** — 按舞种、难度、添加时间筛选历史视频
