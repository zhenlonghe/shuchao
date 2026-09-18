# 书巢（reader）— 极简墨水屏阅读器

需求与边界只看 `SPEC.md`，不得擅自扩展范围。按 M1 → M4 一个里程碑一个 session 推进。

## 环境（本机没有 Android Studio）

- JDK：`/opt/homebrew/opt/openjdk@17`（已写进 `gradle.properties` 的 `org.gradle.java.home`；shell 里跑 `./gradlew` 前仍需 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`）
- Android SDK：`/opt/homebrew/share/android-commandlinetools`（`local.properties`）
- 真机：墨案 Pantone6，adb 直连。实测是 **Android 14 / API 34**、1072×1448、density 300（≈572×772dp），不是 SPEC 写的 Android 11
- 设备会休眠，截图前先 `adb shell input keyevent KEYCODE_WAKEUP`

## 命令

```sh
./gradlew :app:testDebugUnitTest          # JVM 单测（ZipReader / OPF 解析 / 排序）
./gradlew :app:assembleRelease            # release 用 debug 签名，可直接 adb install -r
adb install -r app/build/outputs/apk/release/app-release.apk
```

性能指标一律用 **release** 构建测（debug 下 Compose 冷启动 3s+，没有参考价值）。
冷启动到书架：`adb logcat | grep "Fully drawn"`；扫描耗时：`adb logcat -s LibraryScanner`。

## 进度

- [x] **M1 书架**（2026-09-17）：304 本测试库，冷启动到书架 ≈ 0.8s，全量扫描 ≈ 9s，增量重扫 ≈ 1s，PSS ≈ 39MB
  - 同日按用户要求打磨：4×3、去标题去横线、设置页只剩「书库文件夹」「关于」
- [x] **M2 文字阅读器**（2026-09-17）：最初用 Readium 3.3.0（现在只作为「原版」模式保留，默认走原生文字引擎，见下）。以下是 Readium 的数据：章内翻页 ≈ 125ms，跨章 ≈ 175ms；开书到首页 ≈ 1.4s（章首）/ 1.8s（章中恢复），
  首次开书（WebView 冷）≈ 2.4s —— **略超 §9 的 1.5s**，时间几乎全在 Readium/WebView 内部（我们自己的开书只占 ≈ 250ms）。阅读中 PSS ≈ 160MB
- [x] **M3**：「文字 / 漫画」两种模式各存一套偏好；**不做**对比度增强、RTL 方向切换（用户决定）。
  漫画最初沿用 Readium，用户反馈「进书要等 4s」后改成**直读**（不经 WebView，见下）：真机录屏实测，点开到出图 2.5s → **0.25s**，
  每翻一页 CPU 约 920ms → 85ms
- [x] **M4 墨水屏打磨**（2026-09-17）：录屏逐帧审计（见下）。冷启动到书架 ≈ 0.8–0.9s 且只刷一次屏；阅读页静止时 CPU ≈ 0；
  §7 黑帧整页刷新按用户「刷新按系统的来」**未做**；开书 cold ≈ 2.1s / warm ≈ 1.5s 仍略超 §9（瓶颈在 Readium/WebView 内部，两种预热都试过、不值）

## 用户后续决定（覆盖 SPEC）

- 设置页**不提供**：音量键翻页、默认漫画方向、漫画对比度增强、整页刷新间隔——「这些按系统的来」。对应 DataStore 键已删除。
  M2–M4 涉及这几项时不要再做 app 内开关；具体行为（尤其 §7 黑帧刷新、§5.5 对比度增强是否还做）动手前先和用户确认

## 名字与图标
- 应用名 **书巢**（`strings.xml`）；包名仍是 `dev.zhenlong.reader`
- 图标：自适应图标，`mipmap-anydpi-v26/ic_launcher*.xml` = 纯色底 `@color/ic_launcher_background` + `drawable/ic_launcher_foreground.xml`。
  源文件在 `design/`：`shuchao-icon-original.svg` 是用户给的原稿（墨蓝 #4E6B78），`shuchao-icon.svg` 是针对 Kaleido 调过色的现用版——
  用户要「水墨感」、嫌第一版（#1F5A73）太深：现在是同一种墨的三个浓淡——底 #6F919D（中墨）、后层 #B7CDD5（淡墨）、书页纯白（留白）、书脊露出底色。
  Kaleido 会把颜色压暗，所以取得比原稿还浅；全部平涂，不用渐变（墨水屏上渐变会抖成色带）。
  前景比原稿比例放大了 1.2 倍（墨案桌面的图标蒙版近乎直角方形）

## 已定的实现取舍

### 墨水屏审计（M4）
- **审计方法**：`adb shell screenrecord` 录一段操作 → `ffmpeg -vf mpdecimate` 去重后平铺成一张图，数「一次操作产生了几帧不同画面」。
  瞬切 = 恰好 1 帧。审计时把三个 `*_animation_scale` 设成 1（设备出厂是 0），**做完务必改回 0**
- 结果：书架翻屏 / 进出一套 / 设置 / 阅读翻页 / 工具条 / 面板 / 目录，全部单帧。剩下的多帧只有系统自己的状态栏隐现（scale=0 时也是瞬切）
- 启动只刷一次屏，靠三件事：`windowIsTranslucent`（Android 12+ 无视 `windowDisablePreview`，只有半透明窗口不套启动画面）、
  `windowBackground` 透明 + 书库没备好时 `ShelfScreen` 什么都不画（桌面一直留到完整书架出现）、
  `ReaderApp.preloadFirstScreen` 先把首屏 12 张封面送进 Coil 内存缓存再放出书库数据
- 窗口是透明的，所以**每个页面必须自己铺满白底**，且窗口不能被键盘挤小（会漏出桌面）→ 全 app edge-to-edge
  （`setDecorFitsSystemWindows(false)`），页面用 `stableSystemBarsPadding()`（按「系统栏始终在」算，否则从全屏阅读器退回来时整页会先顶上去再被推下来）+ `imePadding()`
- 阅读器揭开白色遮罩比 `onPageChanged` 晚 120ms：WebView 的画面比回调晚一两帧上屏，否则页脚先于正文出现
- 从阅读器回到搜索状态的书架不再自动弹键盘（只有刚点开搜索时才要焦点）
- Manifest 里移除了 media3 带进来的 `WAKE_LOCK` / `ACCESS_NETWORK_STATE` 权限和 EmojiCompat 启动初始化；无 Service、无 WakeLock、无 KEEP_SCREEN_ON（已用 dumpsys 核对）
- Readium 的链接 / 选区颜色改成纯黑白（`RsProperties`）
- 功耗：设备的 batterystats 给不出分应用数据（电池模型是占位值），没法和系统阅读器做 1.3× 对照，只能测 CPU 时间：
  停在一页上 30s ≈ 50ms；翻一页 ≈ 0.57s（app 进程，含进程内的 Chromium 合成）+ 0.35s（WebView 渲染进程），基本全是 Readium/WebView 的开销


### 原生文字引擎（`reader/text/`，2026-09-17，用户反馈文字书开书 3–4s 之后）
- **文字书默认不再走 Readium / WebView**。真机录屏实测《Apple in China》：白屏到出字 3.9s（第二次 3.1s）→ **0.6s（第二次 0.2–0.4s）**；
  每翻一页 CPU ≈ 920ms → ≈ 60ms；阅读中内存 ≈ 160MB → ≈ 48MB。WebView 这条路试过的上限：去掉 9MB 自定义字体省 0.85s、只加载当前章省 0.4s，
  单章自己仍要 ≈ 1s，到不了「快」
- 结构：`ChapterParser`（jsoup 走 DOM → 带 span 的文字；只认结构：标题 / 段落 / 强调 / 列表 / 引用 / 图片 / 链接，**不认出版商 CSS**）
  → `ChapterLayout`（一章一个 StaticLayout，按行高分页；标题不落页底；`PictureSpan` 大图独占一行缩到版心、小图随文）
  → `TextEngine`（单循环朝最新目标干活；章内翻页同步换页号，跨章才排版，所以下一章提前排好；最多留 3 章；图片在翻到前解码）
  → `NativeTextReader`（Compose Canvas 直接画）
- 细节：行距滑块是 CSS 含义（字号 × 倍数），StaticLayout 的倍数乘的是字体自然行高，要换算；源码里两个汉字间的**换行**不产生空格（真空格保留）；
  段首全角空格去掉、统一用两字缩进；每章一支自己的 TextPaint（后台排版和主线程绘制并发）；自定义字体用 `Typeface.Builder(fd)` 直接映射，22MB 也不慢
- 版心不「偏上」（用户 2026-09-17 反馈）：整页文字多出来的那不到一行的空白上下均分（`ChapterLayout.topInset`），只有一张图的页垂直居中，
  章末半页照常顶着上沿；下留白 = 上留白 − 页脚高 + 6dp（`bottomMarginDp`，页脚算进下边距里），原版模式共用
- 进度存 `locatorJson`，Readium 兼容格式外加 `locations.charOffset`（精确到字）；改字号 / 边距后保持「读到的那个字」不动，而不是页号
- 正文链接可点（脚注、书内目录），返回键回到来处
- 已知取舍：API 34 没有字间两端对齐，中文行尾会有不足一字的参差；表格压成一行一行的文字；竖排、内嵌字体、复杂 CSS 不还原——这类书切「原版」
- **三种模式**（工具条顶栏「文字 原版 漫画」，`BookKind.WEB` 只出现在 `kindOverride`）：文字 = 原生；原版 = Readium（慢，还原出版商版式）；漫画 = 直读。
  切换时从同一章、同一进度接着读

### 文字 / 漫画模式（M3）
- 阅读页入口是 `ReaderHostViewModel` + `ReaderScreen`：开 ZIP、用自己的 OPF 解析拿阅读顺序（`EpubMetaParser.parseSpine`，几十毫秒）、判定模式，
  再选渲染器：**漫画 → `MangaReader`**（`MangaSession` 从 ZIP 找到每页 XHTML 引用的那张图直接解码，Compose `Image` 显示；
  当前页 ±1 预解码、最多留 3 页；非图片页退化成纯文字）；**文字 → `TextReader`**（Readium，`ReaderViewModel` 用到时才创建）
- 为什么漫画不走 Readium：WebView 要初始化 + 同时加载前后三页大图，回调「首页就绪」之后图片还要再解码上屏，真机录屏是 2.5s+；直读就是一次 JPEG 解码
- 漫画的目录不常用：点开时才用 Readium 解析一次（`loadToc`）；目录项 → 章节文档 → 页码
- 两种渲染器都同时写 `locatorJson` 和 `pageIndex`（章节序号），来回切模式能从同一处接着读
- 判定在**开书时**做（规则同 SPEC §6：固定版式，或抽样 12 个章节 ≥ 90% 是「一页一图」），结果写回 `Book.kind`；
  不在扫描时做——书架用不到 kind，而给 500 本 70MB 的书补判定要全部重扫。`MangaDetector` 是纯函数，有单测
- 量开书时间要看**录屏的帧时间戳**（`scratchpad` 里的 opentime 做法：screenrecord + `mpdecimate,showinfo`），不要只信 Readium 的回调——回调比真正上屏早很多
- 工具条顶栏右侧「文字 漫画」切换，当前模式加下划线；手动切换写 `Book.kindOverride`，按书记住
- 两套偏好：DataStore 里漫画的键带 `manga.` 前缀（`Settings.styleFor(kind)`）。漫画默认四边零留白；漫画模式**不显示页脚**（页数在工具条顶栏看），
  底栏只有「边距 / 目录」
- 左右边距档位从 0 起（0 给漫画铺满用）。旧键 `marginStep` 是从 0.25 起的，读旧值时 +1，新键叫 `hMarginStep`

### 翻页与页码（用户 2026-09-17 指定，覆盖 SPEC §5.4）
- 点**左右两侧 1/3 都是下一页**（用户左右手交替单手拿），上一页靠右滑；中间 1/3 呼出工具条。音量键不变
- 页脚右侧是**全书**的「当前页/总页数」，是估算：`PageEstimator`——翻到过的章用真实页数，其余按字节数比例估，至少 1 页；
  改排版后 `reset()`。漫画一章一图所以是精确的；文字书随阅读逐步校准，总页数可能小幅跳动。真分页要把每章都排一遍，太慢，不做


### 阅读器（M2）
- 代码在 `reader/`：`ReaderViewModel`（开书 / 存进度）、`ReaderScreen`（Fragment 宿主 + 工具条 overlay）、`TocScreen`、`ZipContainer`、`SwipeInterceptLayout`
- Readium 版本卡在 **3.3.0**：3.4.0 用 Kotlin 2.4 编译，本工程的 Kotlin 2.2 编译器读不了它的元数据。升 Readium 要先升 Kotlin / KSP
- `ZipContainer` 把 `ZipReader` 接成 Readium 的 `Container`：不走 Readium 自带的 content:// 支持（每次区间读都重新开流 skip，很慢）
- 手势：`SwipeInterceptLayout` 在父层截走所有拖动，WebView 收不到 MOVE → 没有跟手滚动；点按照常交给 Readium（`InputListener.onTap`）
- 音量键在 `MainActivity.dispatchKeyEvent` 拦（焦点在 WebView 上，`onKeyDown` 太晚）；同时认 PAGE_UP/DOWN、DPAD 左右。无开关（见上）
- 页脚「页/总页」来自 `PaginationListener`；改排版后的重排不触发它，用一段 JS 向 WebView 重新问一次
- **用户字体**（2026-09-17 用户要求，超出 SPEC「一期不做字体导入」）：字体面板末项经 SAF 授权一个字体文件夹（选择器默认落在 `/sdcard/Fonts`；
  按路径直接读需要 `MANAGE_EXTERNAL_STORAGE`，SPEC 禁止）。`reader/Fonts.kt`：`FontContainer` 把字体伪装成书内资源挂在
  `https://readium_package/__reader_font/…`（Readium 只肯从 APK assets 或书本身供文件），直接从 SAF 流式读、不复制。
  @font-face 全部声明、惰性加载；声明在渲染器创建时定死，所以字体列表变化会重建一次 Fragment（从 `vm.lastLocator` 接着读）。
  改书库目录时只释放旧书库的授权，不能动字体文件夹的授权
- **字号 / 行距 / 边距都是离散滑块**（用户要求；SPEC 原为 8 档按钮 + 三选一）：字号 12 档（0.8–2.2）、行距 13 档（1.0–2.2）、
  左右边距 12 档（Readium `pageMargins` 0.25–3.0）、上下边距 9 档（0–64dp，默认 24dp）。`StepSlider` 拖动时只动滑块，松手才提交、正文只重排一次
- **上下边距**不是 Readium 的设置（它没有），而是 Compose 给 WebView 容器加的 padding（上 = v，下 = v/2，下面还有页脚）；容器变尺寸后 Readium 会自己重新分页
- **页脚**（用户指定，覆盖 SPEC 的「页数左 / 百分比右」）：左 = 系统时间（监听 `ACTION_TIME_TICK`，不自己起定时器），右 = 当前页/本章页数。
  全书百分比已去掉（`BookProgress` 插值逻辑一并删除，需要时从这条记录重做：章起点 totalProgression + 章内 progression × 本章占比）
- 首页就位前盖一层白：Readium 会先亮出章首再滚到上次位置，不盖的话墨水屏刷两次
- `MainActivity.onCreate` 传 `super.onCreate(null)`：不恢复旧状态，否则进程被杀后 FragmentManager 会在书没打开时重建 Readium Fragment 而崩。代价是回到书架
- `ReaderApp.library` 用 `WhileSubscribed`：阅读时翻页写进度不会触发整库重排
- 试过启动后预热 WebView：首次开书快 0.4s，但书架常驻内存 39MB → 114MB（逼近 §9 的 120MB），已撤
- 测试用长文本书：`reader-test/LongText.epub`（搜 `longtext`），14 章 + 嵌套目录

### 书架（M1）

- SAF 只给 fd，`java.util.zip.ZipFile` 用不了 → 自写 `scan/ZipReader`（FileChannel 读中央目录，随机读条目）。M3 的 ImagePager 复用它
- 书架翻屏：SPEC 写「点屏幕最下方 1/6 的左/右半」，但那块区域和第三行封面重叠，会抢掉开书的点击 → 改为 36dp 页脚（显示 `1 / 3`，左半上一屏、右半下一屏）+ 上下滑
- 网格固定 **4 列 × 3 行**（12 本/屏，用户 2026-09-17 指定，覆盖 SPEC 的 3 列）；封面尺寸取宽、高两个约束的较小值，矮窗口（键盘弹出）自动减行
- 书架顶栏**平时收起**（用户 2026-09-17：搜索 / 设置不常用）：整屏给书，封面因此更大。在第一屏再下拉 → 顶栏以 overlay 盖在封面上方出现
  （只重绘顶上一条，网格不动）；点别处 / 上滑 / 返回键收起，收起的那次上滑不翻屏。顶栏内容：进入一套时是「← 套名」，右侧搜索 / 设置。
  没有顶栏时靠**页脚**知道自己在哪一套（「海賊王　1 / 8」）。没有书架可看的状态（未选书库 / 扫描中 / 空）和搜索状态下顶栏固定显示，否则进不了设置。
  overlay 顶栏下有一条细线（把它和被盖住的封面分开）；除此之外全 app 不画分割线，靠留白分组
- 「共 N 本」那一行只在当前列表里真有成套条目时才留高度；行高 = 网格高 / 行数，余量均摊到行间
- 全屏共用 24dp 边距（`ScreenMargin`）：封面外缘、顶栏图标字形边缘、设置页文字都对齐到这条线
- 封面列间距 = `ScreenMargin`（24dp，用户 2026-09-17 对照微信读书后要求加大；原来是 ≈17dp，边距比间距大、显得挤）；封面到书名 8dp。封面因此略小（219 → 211px 宽），省下的高度自动均摊到行间
- 封面**统一 2:3**（用户 2026-09-17 对照微信读书后要求）：`ContentScale.Crop` 居中裁切铺满槽位，描边画在槽位上。之前是按原比例贴左贴底，
  比例不一的封面让列间距看起来参差。代价：特别瘦长的封面（如海賊王彩色版 ≈ 1:1.78）上下会裁掉一截
- 扫描跳过所有点开头的文件 / 目录（用户书库里有 macOS 的 `._xxx.epub` 伴生文件，会被当成坏书并排到每套第一本）；一套的封面取排序后第一本**有封面**的书
- 书名说明用中间省略（`TextOverflow.MiddleEllipsis`）：同一套的书名区别在末尾「卷01」
- 说明文字行高用 sp 计算进网格高度，系统字号放大时封面让位、文字不裁切
- 搜索框不画光标（闪烁动画关不掉）；`LocalIndication` 用空实现（新版 Compose 不接受 null）
- 书库 / 设置在 `ReaderApp.onCreate` 就开始加载，`ShelfViewModel.ui` 有同步初值，首帧直接是书架
- 测试书库：`python3 tools/gen_test_library.py` 生成 `reader-test/`（304 本合成 EPUB），`adb push reader-test /sdcard/Books/`；设备上已有一份
