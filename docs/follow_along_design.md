> ⚠️ **文档状态（2026-08-16 标注）**：本文档为**历史设计稿**（初版规划，2026-08-09），部分内容可能已过时。
> 当前架构与代码状态以 `PROJECT_OVERVIEW.md`（最新版）为准。要点变更：
> - `FOLLOW`（跟着跳）模式**当前可用**；与之并列的 `DEBUG`（调试，双角骨架浮层）为默认模式。
> - `SEGMENTED`（分段跳）模式**代码保留但已禁用**，仅从切换循环移除。
> - 教师骨架改为**播放时实时检测**（`PoseExtractor.detectLiveFrameAt`）；坐标流水线保留 `transpose` 开关。
> - 相似度 `ComparisonEngine.compare()` 已支持**自动镜像对齐**（正常/镜像两约定取高）。
> 设计意图仍有参考价值，请勿据此直接改代码。

# 跟着跳模式 — 设计方案

> 状态：待审批 | 日期：2026-08-09 | 版本：v1.0

---

## 一、需求概述

新增"**跟着跳**"教学模式，与现有"**分段跳**"模式并存。两种模式由用户在选择视频后启用弹窗里选择。

| 模式 | 适用场景 | 体验特点 |
|---|---|---|
| **分段跳**（已有） | 动作复杂、需要重复练习的动作 | 老师先跳完一段 → 暂停 → 5 秒窗口比对段末静态姿势 → 通过/重试 |
| **跟着跳**（新增） | 整首舞蹈连贯练习、追求体验流畅 | 老师视频从头播到尾不停，用户实时跟跳，系统实时打分 + 表现曲线 |

**用户报告问题**：在大屏（电视）实测分段跳模式，老师跳完整段才暂停的"打断感"破坏舞蹈连贯体验。希望有一种连续跟随的模式，老师一边跳，用户一边跟着跳，边跳边打分。

---

## 二、核心概念：反应延迟（lag）

### 2.1 问题定义

人在跟随领舞时，**看到动作 → 大脑解码 → 身体执行**需要时间。常态经验值：
- 训练有素者：~200-300ms
- 一般用户：~400-600ms
- 复杂动作首学：~700-1000ms

如果直接比对"用户 t 帧"和"老师 t 帧"，分数永远偏低或误判走形。

### 2.2 lag 校准算法

**初始 lag**：500ms（保守值，确保首次体验不被误判低分）。

**自适应 lag**：每 5 秒滑动窗口用 [动态时间规整（DTW）](https://en.wikipedia.org/wiki/Dynamic_time_warping) 在 ±200ms 区间内搜索最优 lag：

```
windowFrames = 5s × 30fps = 150 帧
for shiftMs in [lag - 200, lag - 100, lag, lag + 100, lag + 200]:
    dtwDist = DTW(userFrames[now-150:now], teacherFrames[now-150-shiftMs:now-shiftMs])
if bestShifts < lag:
    lag = max(150ms, lag - 50ms)  // 用户反应变快
if bestShifts > lag:
    lag = min(800ms, lag + 50ms)  // 用户反应变慢
```

**lag 应用范围**：每帧评分、教师视频时间戳、表现曲线、跟丢检测都基于校准后的 lag。

**UI 反馈**：侧边栏底部显示当前 lag（"⏱ 你的反应延迟 ~480ms"），让用户感知自己的反应速度。

---

## 三、教学模式状态机

### 3.1 状态定义

```
IDLE          初始空闲，等待用户选择视频
ANALYZING     正在预处理视频（首次播放走骨架提取）
MODE_PICK     视频就绪后弹窗选择模式（分段跳 / 跟着跳）
COUNTDOWN     倒计时 3 秒，准备开始
FOLLOWING     持续跟随跳（视频不停，实时评分）
PAUSED_LOST   跟丢超过 3 秒，视频暂停，等待用户回到目标区
COMPLETED     整段舞蹈完成，显示总评
```

### 3.2 状态流转

```
IDLE
  │ 用户选视频
  ▼
ANALYZING
  ├─ 有缓存 → 直接加载（< 1秒）
  └─ 无缓存 → 跑 PoseExtractor + 在线分段（首次 ANALYZING 比分段跳多一步：计算 lag 校准的 DTW 索引）
  ▼
MODE_PICK（DialogModePicker）
  ├─ 选"分段跳" → 复用现有 TeachingController 状态机
  └─ 选"跟着跳" → 进入 COUNTDOWN
  ▼
COUNTDOWN（"3, 2, 1, 开始！"）
  │ 倒计时结束
  ▼
FOLLOWING（核心循环）
  │ 每帧 30fps：
  │   1. 取用户当前骨架 userFrame
  │   2. 计算 teacherFrame = poseSeq[videoTimeMs - lag]
  │   3. score = ComparisonEngine.compare(teacherFrame, userFrame, false, false)
  │   4. push score 到表现曲线 RingBuffer
  │   5. 更新 UI：实时相似度 + 表现曲线
  │   6. 跟丢检测（见 3.4）
  │
  ├─ 视频自然结束 → COMPLETED
  ├─ 跟丢 ≤ 3 秒 → 顶部黄字"跟丢了，加速追" → 持续保持 FOLLOWING
  └─ 跟丢 > 3 秒 → PAUSED_LOST
  ▼
PAUSED_LOST
  │ 视频暂停在当前帧
  │ 持续检测用户姿态
  │ 直到相似度 ≥ 阈值 持续 5 帧（~170ms）
  │ 或超时 5 秒 + 仍跟丢 → 状态栏显示"已暂停，按继续键恢复"
  ▼
FOLLOWING（视频从暂停帧继续播放）
  或用户主动点"暂停"→ COMPLETED（提前结束）

COMPLETED
  │ 显示"第 Y 轮完成！平均分 XX%"
  │ 显示表现曲线（最近一轮）
  │ 用户点击"再来一轮"或"换视频"或 5 秒后自动循环
  ▼
COUNTDOWN（新一轮，可选）
```

### 3.3 跟丢检测细节

```kotlin
// 每 50ms tick 一次（与现有 ComparisonEngine 调用节奏一致）
val recentScores = scoreWindow.last(30)  // 1.5 秒窗口
val lowFrac = recentScores.count { it < 35f } / 30f

if (lowFrac >= 0.7f) {  // 1.5 秒内 70% 帧 < 35 分
    lostStreakMs += 50
    if (lostStreakMs >= 3000) {  // 连续 3 秒跟丢
        // 暂停视频 + 切到 PAUSED_LOST
    } else if (lostStreakMs >= 1000) {
        // 显示黄色提示"跟丢了，加速追"
    }
} else {
    lostStreakMs = max(0, lostStreakMs - 100)  // 缓慢恢复
}
```

跟丢阈值默认 35 分（低于分段跳的 CHECKING 阈值 70，因为连续动作比对更苛刻）。

### 3.4 PAUSED_LOST 恢复逻辑

```
PAUSED_LOST 阶段：
  - 视频暂停在 pausedAtMs
  - 每帧持续评估 userFrame vs teacherFrame[pausedAtMs - lag]
  - 如果 score ≥ 暂停阈值 持续 5 帧 → 恢复 FOLLOWING（视频从 pausedAtMs 继续）
  - 如果 5 秒内未恢复 → 顶部显示"已暂停，按继续键恢复" + 侧边栏"继续"按钮亮起
  - 用户点"继续" → 视频从 pausedAtMs 继续，状态回 FOLLOWING
  - 用户点"跳过"（段短的，"已暂停"二次退出） → 直接 COMPLETED
```

---

## 四、表现曲线（Performance Curve）

### 4.1 概念

横轴：视频时间（0 ~ 整段时长）
纵轴：相似度分数（0 ~ 100）
实时把 score 画到坐标系上，让用户看到：

- 哪个时间点掉队了
- 哪个时间点保持得好
- 整体趋势是上升/下降/平稳

### 4.2 实现

```kotlin
class PerformanceCurve(window: Int = 600) {
    // 600 帧 = 20s @ 30fps，覆盖最近 20 秒
    private val buffer = ArrayDeque<Pair<Long, Float>>(window)

    fun push(timestampMs: Long, score: Float) {
        buffer.addLast(timestampMs to score)
        while (buffer.size > window) buffer.removeFirst()
    }

    fun draw(canvas: Canvas, width: Float, height: Float) {
        // 背景：灰
        // pen: 白色
        // 每帧画一段线
        // 转折点用小圆点
        // 阈值线（35 分）用红色虚线
    }
}
```

### 4.3 UI 集成

- 埋在 `activity_teaching.xml` 顶部状态栏下方
- 高度 ~60dp
- 跟随视频进度横向滚动（最近 20s 窗口）
- 暂停时定格当前曲线

---

## 五、模式选择入口

### 5.1 DialogModePicker

视频 ANALYZING 完成后弹出，让用户选模式。

```
┌─────────────────────────────────────┐
│        选择教学模式                  │
├─────────────────────────────────────┤
│                                     │
│  ◯ 分段跳                            │
│     老师先跳完一段，暂停后你再跳       │
│     ✓ 适合复杂动作                  │
│     ✓ 反复练习                      │
│                                     │
│  ◉ 跟着跳                            │
│     老师跳你也跟着跳，连续不断        │
│     ✓ 流畅体验                      │
│     ✓ 适合完整舞蹈                  │
│                                     │
│  ┌─────────────────────────┐        │
│  │ ☐ 记住选择（下次默认）  │        │
│  └─────────────────────────┘        │
│                                     │
│      [取消]      [开始]              │
└─────────────────────────────────────┘
```

### 5.2 记忆选项

勾选"记住选择" → 写到 `SharedPreferences`（key = `mode_preference`）：
- `ASK_EVERY_TIME`（默认）
- `ALWAYS_SEGMENTED`
- `ALWAYS_FOLLOW`

应用重启后从 SharedPreferences 读取，决定是否跳过弹窗。

### 5.3 切换模式

用户在教学界面侧边栏可点"切换模式"按钮 → 重新弹 DialogModePicker → 切换后回到 COUNTDOWN。

---

## 六、UI 改动

### 6.1 顶部栏（跟着跳模式特有）

```
┌────────────────────────────────────────────────────────────────┐
│ [🏠] 跟着跳 · 第2轮   实时 75%  ⚠️ 跟丢了   ⏱lag 480ms   [⏸] │
├────────────────────────────────────────────────────────────────┤
│ 表现曲线 ▁▂▃▅▇█▇▆█▇▆▅▄▃▂▁▂▃▅▇█▇▆ ▁▂▃▅▇█▇▆▅▄▃                  │
└────────────────────────────────────────────────────────────────┘
```

- "分段跳"三段文字（"第 X/N 段 · 难度 · 轮次"）替换为"跟着跳 · 第 X 轮"
- 实时分数 + 跟丢提示 + 当前 lag 一起显示
- 表现曲线作为第二行

### 6.2 侧边栏新增按钮

| 按钮 | 位置 | 行为 |
|---|---|---|
| 切换模式 | 顶部分隔线下方 | 弹 DialogModePicker |
| 暂停 | 已有 | 立即进入 COMPLETED（与分段跳语义不同） |
| 继续 | PAUSED_LOST 状态时亮起 | 视频从暂停帧恢复 |
| 调 lag | 高级调校 | 弹 Slider，让用户手动指定 lag（±100ms） |

### 6.3 视频区不变

- 老师视频：循环到视频末尾 → COMPLETED（同分段跳）
- 摄像头：仍为左半 / 右半显示
- 骨架叠加：师生对比 + 实时 score 颜色渐变（红 0-40 / 黄 40-70 / 绿 70-100）

### 6.4 PAUSED_LOST 视觉反馈

- 视频半透明灰覆盖
- 中央显示"⏸ 暂停中，请回到老师当前姿势"
- 持续到恢复或超时

---

## 七、评分体系（跟着跳专属）

### 7.1 轮次平均分

```
roundScore = average(performanceCurve.scores)
            // 包含所有评分帧，不只是峰值
```

### 7.2 跟丢惩罚

```
lossPenalty = lostStreakMs × 0.001  // 1 秒跟丢扣 1 分
roundScore = round(performanceCurve.average - lossPenalty).coerceAtLeast(0)
```

### 7.3 展示

```
"本轮完成！"
"平均分：78%"
"最佳时刻：92%（第 23.5 秒）"
"最差时刻：41%（第 41.2 秒）"
"跟丢时长：2.3 秒（×2 次）"
"你的反应延迟：~420ms"
```

### 7.4 与分段跳分数隔离

分段跳段得分和跟着跳轮次分独立存档：
- `VideoLibraryEntry.segmentedBestScore`（已有）
- `VideoLibraryEntry.followAlongBestScore`（新增）

---

## 八、数据模型

### 8.1 新增数据结构

```kotlin
/** 跟着跳模式单轮成绩 */
data class FollowAlongScore(
    val round: Int,
    val avgScore: Float,           // 0..100
    val peakScore: Float,          // 0..100
    val peakAtMs: Long,            // 峰值所在视频时间
    val worstScore: Float,         // 0..100
    val worstAtMs: Long,           // 最低分所在视频时间
    val totalLostMs: Long,         // 跟丢累计时长
    val lostCount: Int,           // 跟丢次数
    val finalLagMs: Long,          // 末端 lag
    val curveSamples: List<Pair<Long, Float>>  // 表现曲线采样（降采样到 1Hz 存储）
)

/** 在 VideoLibraryEntry 上新增 */
data class VideoLibraryEntry(
    // ... 已有字段
    val followAlongBestScore: Float = -1f,  // 跟着跳历史最高分
    val followAlongBestLagMs: Long = -1,    // 跟着跳历史最佳 lag
    val lastFollowAlongAt: Long = 0         // 上次跟着跳时间戳
)
```

### 8.2 持久化

- 单轮 FollowAlongScore 写入 `cacheDir/<cacheId>/follow_history.json`
- VideoLibraryEntry 同步更新到 `library.json`
- 表现曲线只保存降采样到 1Hz 的 100~200 个采样点，避免 JSON 过大

---

## 九、配置与算法复用

### 9.1 复用现有模块

| 模块 | 复用方式 |
|---|---|
| PoseSequenceStore | 现有骨架提取 + 缓存复用 |
| Segmenter | **不复用**（跟着跳不需要分段） |
| ComparisonEngine | 完全复用，逐帧比对 |
| PoseSequence | 完全复用 |
| TeachingController | **不直接复用**，但新建 `FollowAlongController` 复制状态机骨架 |
| VideoLibrary / VideoCacheManager | 复用，新增 `followAlongBestScore` 字段 |
| VideoPicker | 完全复用 |
| CameraManager | 完全复用 |
| ExoPlayer | 完全复用 |
| CastManager | 完全复用（电视同步一致） |

### 9.2 新增模块

| 模块 | 职责 |
|---|---|
| `FollowAlongController` | 状态机 + lag 校准 + 持续评分 |
| `PerformanceCurve` | 表现曲线环形缓冲 + 渲染 |
| `LagCalibrator` | DTW 自适应 lag 计算 |
| `DialogModePicker` | 模式选择弹窗 |
| `PerformanceCurveView` | 自定义 View，绘表现曲线 |

### 9.3 落地文件清单

```
app/src/main/java/com/dancecoach/app/follow/
  ├── FollowAlongController.kt
  ├── LagCalibrator.kt
  ├── PerformanceCurve.kt
  └── DialogModePicker.kt

app/src/main/java/com/dancecoach/app/ui/
  └── PerformanceCurveView.kt

app/src/main/res/layout/
  ├── dialog_mode_picker.xml
  └── (activity_teaching.xml 顶部加表现曲线 + 状态栏改文案)

app/src/main/res/drawable/
  ├── ic_follow_along.xml
  └── ic_switch_mode.xml
```

---

## 十、兼容性 & 风险

### 10.1 兼容性

- **API**：split 21，min/target SDK 不变
- **依赖**：不引入新第三方库，DTW 用 50 行 Kotlin 手写
- **存 储**：VideoLibraryEntry 字段新增有默认值，向后兼容
- **数据库**：无引入

### 10.2 风险

| 风险 | 缓解 |
|---|---|
| lag 校准初期不准导致首轮分数虚低 | 起步 lag = 500ms（保守），5 秒后开始校准；UI 显示当前 lag 让用户感知 |
| DTW 计算开销大 | 滑动窗口 150 帧 × 5 个 shift = 750 帧比对，30fps 下 < 1ms CPU，可接受 |
| 跟丢检测阈值 35 分是否太宽松 | 首版固定 35，灰度后允许用户在设置里调 |
| 表现曲线画到 UI 上耗 GPU | 60dp 高度 × 1080 宽 = ~6 万像素，绘制 < 1ms |
| 模式选择弹窗破坏流畅体验 | 默认"记住选择"，勾选后下次跳过弹窗 |
| 模式切换后视频位置不一致 | 切换模式回到视频开头（COUNTDOWN），避免位置错乱 |

### 10.3 验收标准

- [ ] 选视频后弹模式选择，原"分段跳"用户不受影响
- [ ] 分段跳的所有回归测试通过
- [ ] 跟着跳：完整跟完 30 秒测试视频不崩溃
- [ ] 跟着跳：表现曲线实时更新，UI 帧率不掉
- [ ] 跟着跳：lag 校准从 500ms 收敛到稳定区间（5 秒内）
- [ ] 跟丢暂停：故意停下 3 秒，视频自动暂停,姿态恢复后自动继续
- [ ] COMPLETED 显示本轮统计 + 表现曲线
- [ ] 库历史显示分段跳 / 跟着跳双字段
- [ ] 投屏同步：跟着跳模式在电视上同样能实时显示

---

## 十一、Phase 任务拆分

**Phase 1（MVP，不能切阶段）：**
1. DialogModePicker 弹窗（不勾选记忆）
2. FollowAlongController 状态机（IMPL 走通）
3. LagCalibrator + 固定 lag = 500ms（先不上 DTW）
4. 持续评分 UI（实时相似度 + 跟丢提示）
5. COMPLETED 显示本轮统计

**Phase 2（优化）：**
6. DTW 自适应 lag
7. 表现曲线绘制
8. 模式记忆 SharedPreferences
9. 库历史双字段持久化
10. 切换模式按钮

**Phase 3（高阶）：**
11. 调 lag 手动 Slider
12. 跟丢暂停恢复逻辑
13. 投屏同步验证

---

## 十二、待你确认的设计点（已确认）

> 2026-08-09 二次审核结论，以下 5 项均已确认。

### 1. 模式选择入口：侧边栏状态按钮 + 举重姿势手势切换

- **不弹模式选择弹窗**。
- 左侧边栏顶部第 1 组加一个状态显示按钮（仅显示当前模式）：
  ```
  ┌─────────────┐
  │  跟着跳  ⇄  │  ← 按钮（点击无效，仅显示当前模式）
  └─────────────┘
  ```
  按钮文字根据当前模式动态切换：
  - 跟着跳模式：`跟着跳 ⇄`（按下"切换模式"后变为切换目标）
  - 分段跳模式：`分段跳 ⇄`
- **模式切换通过"举重姿势"手势触发**（不能点按钮）：
  - 触发时机：每次跳完一轮进入 COMPLETED 状态后
  - 手势定义：双臂同时弯曲抬起（类似举重的起始姿势），肘部夹紧躯干，前臂向上
  - 手势识别在 IDLE / COMPLETED 状态下持续工作
  - 识别到且持续 1.5 秒 → 完成切换
  - 切换后：
    - 视频回到开头
    - 状态列队回到 COUNTDOWN
    - 模式持久化到 SharedPreferences
- **默认模式**：跟着跳（新开发的模式）
- **持久化**：SharedPreferences（key = `mode_preference`，value = `FOLLOW` / `SEGMENTED`），首次启动默认为 FOLLOW

### 2. 舞蹈时长上限：2 分钟，截断处理

- 视频时长 > 120 秒 → 只取前 120 秒（视频 seek 范围 0..120000ms）
- 实现位置：选视频后调用 `MediaMetadataRetriever` 取 `durationMs`，超标就在 `PoseExtractor` 预处理 / `ExoPlayer.prepare` 时限定 120 秒
- 不显式提示用户"已截断"，因为分段跳模式本身也没做时长警告，保持一致
- ExoPlayer 走 `setPlayWhenReady(true)` 后自然在 120 秒处停止，触发 COMPLETED

### 3. COMPLETED 后行为：跟分段跳相同

- 复用现有 COMPLETED 逻辑：
  - 显示"第 Y 轮完成！平均分 XX%"
  - 显示历史最佳
  - 2 秒后自动回到 COUNTDOWN（第 0 帧 / 视频开头）
  - 无限循环
- 跟着跳的 COMPLETED 额外显示：
  - 表现曲线（最近 20s 滑动窗口）
  - 反应延迟（最终 lag）
  - 跟丢（取消，按 4 不再做）
- 不要新弹"再来一轮 / 换视频"按钮，沿用分段跳 UI

### 4. 不做跟丢检测 / 跟丢暂停

- 取消 3.3 节跟丢检测算法
- 取消 3.4 节 PAUSED_LOST 状态
- 状态机简化：
  ```
  IDLE → ANALYZING → COUNTDOWN → FOLLOWING → COMPLETED
  ```
- 用户跟丢了系统不干预，纯靠打分反映在表现曲线上
- 移除 6.4 节 PAUSED_LOST 视觉反馈
- 移除 7.2 节跟丢惩罚
- 移除 7.3 节跟丢时长统计

### 5. 固定 lag = 500ms，不上 DTW

- 取消 2.2 节 lag 校准算法
- lag 初始化为 500ms，整个会话不变
- 取消 `LagCalibrator` 类
- 取消 UI 上"⏱ lag 480ms"显示（固定 500ms 没意义）
- 表现曲线 / 评分 / 视频时间戳比对均用 `videoTimeMs - 500ms`
- 留存接口：留 `followAlongLagMs` 字段在 SharedPreferences，下次想升级到 DTW 时只需替换固定值为动态值
