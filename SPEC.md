# 极简墨水屏阅读器 — 一期规格

> 目标设备：墨案 Pantone 6（6 英寸 Kaleido 3 彩屏，1072×1448，黑白 300PPI / 彩色 150PPI，RK3566，4GB RAM，Android 11）。
> 本文是一期唯一的需求与技术边界，实现时不得擅自扩展范围。

## 1. 产品定义

一个纯本地的电子书 + 漫画阅读器。打开即书架，书架陈列用户自己按文件夹整理好的书；打开书后只做阅读该做的事。一切以「安静、克制、快」为准。

**一期做：**
- 书架（封面网格，文件夹 = 一套）
- 文字类 EPUB 阅读（分页、字号、字体、行距、边距）
- 漫画类 EPUB 阅读（整页位图翻页、可从右向左）
- SAF 选书库根目录、手动扫描、封面缓存、阅读进度持久化
- 右上角：搜索、设置

**一期明确不做（非目标）：**
- MOBI / AZW3 / CBZ / PDF（二期）
- 账号、同步、书城、云端任何东西
- 批注、高亮、书签、TTS、字典
- 深色模式、主题色、动画、图标装饰
- Dock / 底部 Tab / 分类页 / 个人页
- 书架内手动建文件夹、拖拽、多选管理
- 横屏双页

## 2. 墨水屏硬约束（全局适用，违反即 bug）

1. **零动画。** 页面切换、文件夹进出、工具条显隐、按钮按压全部瞬切。禁用 ripple（`LocalIndication provides null`）、禁用 Compose 任何 `animate*` / `AnimatedVisibility` / `Crossfade`、禁用 Activity 转场动画（`overridePendingTransition(0,0)` 或主题中关闭）。
2. **高对比、无灰阶装饰。** 纯白底 `#FFFFFF`、纯黑字 `#000000`、分割线 1px 纯黑。不用阴影、圆角卡片、半透明遮罩、渐变。次要文字用 `#000000` 但字号更小，不用灰色（Kaleido 灰阶显示脏）。彩色只出现在封面和漫画内容里。
3. **不依赖厂商刷新 SDK。** 墨案没有公开的刷新模式接口，app 不得假设能切换刷新模式。所有流畅感来自：不动画、重绘面积小、内容提前准备好。
4. **无后台。** 不启动前台/后台 Service、不用 WorkManager 周期任务、不监听文件变化。扫描只在用户点「刷新」时进行。
5. **重绘面积最小化。** 翻页只重绘内容区；工具条出现时只重绘工具条区域（用 overlay，不 recomposition 整页）。
6. **书架不做连续滚动。** 书架按「整屏」翻页（上/下一屏），避免 e-ink 上滚动拖影。

## 3. 技术选型

| 项 | 选择 | 说明 |
|---|---|---|
| 语言/UI | Kotlin + Jetpack Compose（Material3 仅用组件骨架，主题完全自定义） | minSdk 30, targetSdk 30 |
| 文字 EPUB 渲染 | Readium Kotlin Toolkit（shared / streamer / navigator） | WebView 分页模式，`scroll = false` |
| 漫画渲染 | 自研 `ImagePager`：ZipFile 读图 + `BitmapFactory` 按屏幕尺寸 `inSampleSize` 解码 | 不走 WebView |
| 数据库 | Room | Book / Series 两张表 |
| 偏好 | DataStore (Preferences) | 阅读设置、书库 URI |
| 缩略图 | Coil，磁盘缓存 | 封面缩略图落到 app 私有目录 |
| 文件访问 | Storage Access Framework（`ACTION_OPEN_DOCUMENT_TREE` + persistable permission） | 不申请 `MANAGE_EXTERNAL_STORAGE` |
| 架构 | 单 Activity，Compose Navigation（无转场），MVVM，Kotlin Coroutines | 无 DI 框架也可（项目小），如用则 Hilt |

## 4. 书库组织规则（用户已按文件夹整理好，app 只读镜像）

用户在设置里选一个**根目录**。扫描规则：

- 根目录下直接的 `.epub` 文件 → 书架上的**单本**。
- 根目录下每个**子目录** → 书架上的**一套**（Series）。该子目录下（递归任意深度）所有 `.epub` 都属于这一套，扁平化，不再分层。
- 其他文件类型一期忽略（不报错、不显示）。
- 书架顶层排序：**按名称固定顺序**（单本按书名，一套按文件夹名，混排；使用 `Collator` 做中文/自然数字排序，"第2卷" 在 "第10卷" 前）。
- 一套内部排序：按书名（同上 Collator），书名取不到时按文件名。
- 一套的封面：文件夹内若有 `cover.jpg` / `cover.png` 直接用；否则用该套排序第一本书的封面。
- 单本封面：EPUB 元数据 cover；取不到时生成一个纯白底、黑色书名的占位封面（不要图标、不要灰底）。
- 扫描是**增量**的：以文件 URI + lastModified + size 为 key，未变化的不重新解析。首次扫描显示一个纯文本进度「正在扫描 12/91」，不做进度条动画。

## 5. 界面

### 5.1 书架（首屏）

- 顶部一行：左「书架」标题（进入一套时显示套名，左侧一个「←」纯线条返回），右侧两个线条图标：搜索、设置。无其他元素。
- 内容：3 列封面网格，按整屏分页。每屏行数由屏幕高度算出（Pantone 6 预期 3 行，即 9 本/屏）。翻屏：上下滑动、或点屏幕最下方 1/6 区域的左/右半（上一屏/下一屏），瞬切。
- 每格：封面（固定 2:3 比例，`contentScale = Fit`，纯黑 1px 描边，无圆角）+ 下方一行书名（单行省略）。一套在书名下加一行小字「共 N 本」。
- **封面上不显示任何进度、角标、勾选。**
- 点单本 → 直接进入阅读器；点一套 → 进入该套的同样网格。
- 空状态：只有一句居中文本「在设置里选择书库文件夹」，点击直接跳设置。

### 5.2 搜索

- 点搜索图标：顶部一行变成输入框（不新开页面），即时过滤当前书架（书名 / 作者 / 文件名，模糊、不分大小写），结果以同样网格显示，搜索范围为整个书库（包含套内的书）。
- 清空或返回即恢复。无搜索历史、无热词。

### 5.3 设置

纯文本列表，每行一个设置，无分组卡片：
- 书库文件夹：显示当前路径，点击重新选择；旁边「重新扫描」
- 音量键翻页：开/关（默认开）
- 默认漫画阅读方向：从左到右 / 从右到左（默认从右到左）
- 漫画对比度增强：开/关（默认开）
- 整页刷新间隔：关 / 每 5 页 / 每 10 页 / 每 20 页（默认每 10 页）— 见 §7
- 关于：版本号一行

### 5.4 文字书阅读器

- 全屏，无状态栏（沉浸式），无页眉。页脚一行极小文字：`当前页/本章页数` 左对齐，全书百分比右对齐，纯黑。
- 翻页：点左 1/3 上一页，点右 1/3 下一页；音量键上/下；左右滑动（阈值 48dp，无跟手动画，抬手瞬切）。
- 点中间 1/3：显示/隐藏工具条（顶部一行：← 返回、书名；底部一行：字号 − / +、字体、行距、边距、目录）。工具条为 overlay，不动其他内容。
- 设置面板（点字体/行距/边距）：底部弹出纯白面板，黑边线，选项为文本按钮，选中项加下划线或反色。改动**立即应用**并持久化（全局，不是每本书单独）。
  - 字号：8 档
  - 字体：系统衬线 / 系统无衬线 / 出版商默认（一期不做字体导入）
  - 行距：1.2 / 1.5 / 1.8
  - 边距：窄 / 中 / 宽
- 目录：纯文本列表（缩进表示层级），点击跳转。
- 进度：Readium `Locator` JSON 存到 Book 表，每次翻页写入（防抖 1s），退出时强制写入。再次打开从 Locator 恢复。
- 主题：只有白底黑字。关闭 Readium 的所有过渡效果。

### 5.5 漫画阅读器

- 全屏，无任何 UI 元素；页脚不显示（漫画页面本身通常有页码）。
- 每次显示一整页，`Fit` 到屏幕（默认适配宽度，超出部分不滚动而是缩小到整页可见；一期只提供「整页适配」一种）。
- 翻页方向按设置（默认 RTL：点左 1/3 = 下一页）；音量键上=上一页、下=下一页，不受方向影响。
- 点中间：显示极简 overlay：← 返回、书名、当前页/总页、方向切换（LTR/RTL）、对比度增强开关。
- 对比度增强：解码后对位图做一次简单的对比度 + 轻微锐化（`ColorMatrix` 即可，不引入 OpenCV），彩色保留。
- 进度：存页索引。
- 预加载：当前页 ±1 页解码好并持有；再外一圈只做文件级预读（不解码）。位图缓存上限 = 3 张全屏位图，超出即回收。

## 6. 漫画 EPUB 判定

扫描时解析 OPF，满足以下任一即标记 `kind = MANGA`，否则 `kind = TEXT`：
1. `<meta property="rendition:layout">pre-paginated</meta>`；
2. spine 中 ≥ 90% 的 XHTML 文档满足：正文只含 1 个 `<img>` 或 `<svg><image>`，且可见文本 < 20 字符。

漫画模式下不经 Readium navigator：直接按 spine 顺序解析每个 XHTML 引用的图片路径，从 ZIP 中读取图片流。判定错误时，用户可在阅读器 overlay 中「以文字模式打开 / 以漫画模式打开」切换，切换结果写入 Book 表覆盖自动判定。

## 7. 残影处理（不依赖 SDK 的替代方案）

按设置的间隔，在第 N 次翻页时先绘制一帧全黑（内容区），下一帧再绘制目标页。这会触发系统整页刷新以清残影。此功能可关闭。实现为阅读器层通用逻辑，文字书与漫画共用。

## 8. 数据模型

```kotlin
@Entity data class Series(
  @PrimaryKey val id: String,       // 文件夹 URI 的 hash
  val folderUri: String,
  val name: String,
  val coverPath: String?,           // 缓存缩略图本地路径
)

@Entity data class Book(
  @PrimaryKey val id: String,       // 文件 URI 的 hash
  val fileUri: String,
  val seriesId: String?,            // null = 根目录单本
  val title: String,
  val author: String?,
  val fileName: String,
  val kind: BookKind,               // TEXT | MANGA
  val kindOverride: BookKind?,      // 用户手动切换
  val coverPath: String?,
  val lastModified: Long,
  val size: Long,
  val locatorJson: String?,         // TEXT 进度
  val pageIndex: Int?,              // MANGA 进度
  val pageCount: Int?,              // MANGA 总页
  val lastOpenedAt: Long?,
)
```

DataStore 键：`libraryRootUri`, `volumeKeyTurn`, `mangaDirection`, `mangaContrast`, `fullRefreshEvery`, `fontSizeStep`, `fontFamily`, `lineHeight`, `margin`.

## 9. 性能与功耗预算（验收指标，在真机上测）

- 冷启动到书架首屏可交互：< 1.0s（有缓存时）
- 文字书翻页（已预渲染）：< 300ms 内容出现
- 漫画翻页：< 150ms
- 打开一本已缓存的书到首页出现：< 1.5s
- 书架 300 本时内存：< 120MB；漫画阅读中：< 200MB
- 阅读 1 小时的电量消耗不高于系统自带阅读器的 1.3 倍（粗略对照即可）
- 阅读时**不**持有 WakeLock、不 keep-screen-on（交给系统亮屏超时）

## 10. 里程碑（每个里程碑真机可运行）

**M1 书架**
- SAF 选根目录、扫描、Room 落库、封面缓存、3 列整屏分页网格、进入一套、搜索、设置页骨架
- 验收：300 本书库扫描后重启 < 1s 出书架；翻屏无动画；封面无进度标记

**M2 文字阅读器**
- 集成 Readium，分页、翻页手势、音量键、工具条、四项排版设置、目录、进度恢复
- 验收：设置改动立即生效；退出再进回到原位置；翻页 < 300ms

**M3 漫画阅读器**
- 漫画判定、ImagePager、RTL、对比度增强、预加载与位图回收、模式手动切换
- 验收：91 本一套的漫画连续翻 100 页不 OOM；翻页 < 150ms

**M4 墨水屏打磨**
- 全局动画审计（用 `adb shell settings put global animator_duration_scale 1` 下肉眼检查所有交互无过渡）、整页刷新间隔、电量对照、灰色清理
- 验收：§2 六条逐项通过

## 11. 二期候选（不在本次实现）

MOBI/AZW3（libmobi JNI 或导入时转 EPUB）、CBZ/图片文件夹、PDF、字体导入、书签、按最近阅读排序的可选开关、局域网导入（浏览器上传）。
彩色划线功能

