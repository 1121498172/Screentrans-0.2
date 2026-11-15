# 屏幕翻译（Discord / Patreon）

Android 屏幕内英文原地变中文的覆盖层翻译工具。用**无障碍服务**读取界面文本，用 **TYPE_ACCESSIBILITY_OVERLAY** 把译文画在原控件位置，不需要 Root，也不需要「显示在其他应用上层」权限。

- **离线秒出**：ML Kit 端侧英中模型，几十毫秒先贴一版
- **LLM 精修**：OpenAI 兼容接口（默认 DeepSeek）批量翻译，专门处理黑话、梗、游戏术语
- **排版对齐**：按原控件矩形实测字号、换行、对齐方式，不是简单糊一层
- **术语表**：内置 Discord / Patreon / 游戏常用译法，可追加自定义
- **白名单**：只翻译你勾选的应用，自动跳过密码框、输入框

> 目标机型：小米 13（fuxi）/ HyperOS 1.0（Android 14）。minSdk 29，其他 Android 10+ 机型同样可用。

---

## 0.2.0 改了什么

| 问题 | 处理 |
|---|---|
| 深色 App 上糊一层白底 | 用 `AccessibilityService.takeScreenshot()` 截一帧，**在每个控件矩形内采样众数颜色**当背景，深/浅色自动适配；截图只在覆盖层绘制前拍，避免把自己的色块采样进去 |
| 译文和原文叠在一起 | 按矩形排序去重，被大块完全包含的节点不再重复绘制 |
| 用户名/昵称也被贴块 | 译文与原文相同（忽略大小写）时直接跳过；中英混排且以中文为主的文本不翻 |
| 端侧机翻抢先上屏、看着像机翻 | 混合模式下端侧只兜底**短 UI 标签**；聊天长句宁可空 1 秒等 LLM，也不先糊一版「微生物」 |
| 不知道到底有没有走 DeepSeek | 设置页新增「LLM 状态」：没填 Key / 仅离线 / 上次报错原因都会直接显示 |
| 黑话翻得生硬 | prompt 重写：用户名不音译、脏话保留粗俗度、foreshadowing=伏笔、jacked=肌肉发达、micropenis=屌小 等 |

> **关键**：真正的翻译质量来自 LLM。设置里必须填 API Key 并选「离线先出 + LLM 精修」或「仅 LLM」，「仅离线」永远是机翻水平。

---

## 一、构建

仓库里没有附带 `gradle-wrapper.jar`（二进制文件不便内置），三种方式任选：

### 方式 A：GitHub Actions 出 APK（最省事）

1. 新建一个 GitHub 仓库，把整个 `screentrans` 目录推上去；
2. 仓库自带 `.github/workflows/build.yml`，push 后自动开始构建；
3. 打开 Actions → 最新一次运行 → 底部 Artifacts 下载 `app-debug`，解压得到 `app-debug.apk`。

### 方式 B：Android Studio

1. 新建一个 **Empty Views Activity** 项目：包名 `com.screentrans`，语言 Kotlin，minSdk 29；
2. 用本仓库的 `app/src`、`app/build.gradle.kts`、根 `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties` 覆盖新项目对应文件；
3. Sync → Build → Build APK(s)。

### 方式 C：命令行

需要 JDK 17、Android SDK（platform 34 + build-tools）、Gradle 8.7：

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

---

## 二、安装后的必要设置（HyperOS 会拦）

1. **允许受限设置**（Android 13+ 侧载应用默认不让开无障碍）
   设置 → 应用设置 → 应用管理 → 屏幕翻译 → 右上角 ⋮ → **允许受限设置**

2. **开启无障碍**
   设置 → 无障碍 → 已下载的应用 → 屏幕翻译 → 开启

   也可以用 ADB 直接开（跳过 UI 里的各种拦截）：

   ```bash
   adb shell settings put secure enabled_accessibility_services com.screentrans/com.screentrans.TranslateService
   adb shell settings put secure accessibility_enabled 1
   ```

3. **防止被系统杀**
   应用信息 → 省电策略 → **无限制**；权限里允许**自启动**；最近任务卡片下拉**锁定**。

4. 回到 App 填 API Key（DeepSeek 默认 `https://api.deepseek.com/v1` + `deepseek-chat`），点「测试连接」，再打开总开关。

---

## 三、机型适配说明

| 项目 | 说明 |
|---|---|
| 覆盖层窗口 | `TYPE_ACCESSIBILITY_OVERLAY`，无需悬浮窗权限，不抢焦点和点击 |
| 配色 | Discord 深色 `#313338` / 浅色 `#FFF`，Patreon、X、Reddit 各有预设，也可手动切深/浅 |
| 滚动 | 滚动瞬间先撤译文，260ms 后按新位置重扫，避免文字「漂移」 |
| 事件风暴 | 扫描排队用「最早截止时间」而不是纯防抖，防止 Discord 连续事件把扫描饿死 |

---

## 四、代码结构

```
app/src/main/java/com/screentrans/
├── MainActivity.kt          设置界面：权限、引擎、外观、白名单、术语表
├── TranslateService.kt      无障碍服务：事件调度 + 扫描节流 + 生命周期
├── Scanner.kt               节点树 → 可翻译文本 + 屏幕矩形（过滤输入框/密码框/重复）
├── OverlayController.kt     覆盖层窗口与逐个控件的绘制、精修后原地更新
├── Coordinator.kt           一次扫描的编排：缓存 → 离线 → 上屏 → 丢进 LLM 队列
├── OfflineTranslator.kt     ML Kit 端侧翻译 + 模型下载与失败退避
├── LlmTranslator.kt         OpenAI 兼容批量翻译 + JSON 解析 + 重试
├── LlmRefiner.kt            LLM 请求合批、去重、失败冷却
├── TextMeasure.kt           按矩形实测最大可容纳字号（排版核心）
├── Glossary.kt              内置术语表 + 自定义解析
├── AppProfile.kt            各 App 的配色
├── Prefs.kt                 设置持久化
├── TranslationCache.kt      LRU 译文缓存（离线结果不会被 LLM 结果回退）
├── QsTile.kt                下拉快捷开关
└── Models.kt                RawItem / RenderItem
```

---

## 五、已知限制

- **图片里的文字翻不了**：Discord 表情包、Patreon 图片帖里的英文需要 OCR 或视觉模型，当前版本没做（预留了扩展点）。
- **WebView 深层内容**：部分混合应用的内嵌网页节点暴露不全，可能漏译。
- **输入框不翻**：避免覆盖用户正在输入的内容，这是刻意设计。
- **覆盖层是「贴纸」不是「原生替换」**：无障碍 API 无法修改别的 App 的 TextView，只能画上去；滚动时可能有一帧错位。
- **离线模型下载依赖 Google 服务**：国行网络大概率失败，失败后自动退化为纯 LLM 模式，不影响使用。
- **每次 API Key 明文存本机**：仅自用场景，别把装好的机器借人。

---

## 六、下一步可加

1. OCR 兜底（MediaProjection + ML Kit Text Recognition）覆盖图片内文字
2. 长按译文复制原文 / 对照模式
3. 悬浮球开关（现在只有快捷磁贴）
4. 按 App 记忆独立术语表与配色
