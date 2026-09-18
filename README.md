# 书巢

极简墨水屏 EPUB / 漫画阅读器（Android）。只做一件事：把整理好的本地书库摆上书架，打开就能读。

<img src="design/shuchao-icon.svg" width="96" alt="书巢图标">

- **快**：冷启动到书架 ≈ 0.8s；文字书点开到出字 ≈ 0.6s，漫画 ≈ 0.25s；翻一页 CPU ≈ 60–85ms
- **为墨水屏而做**：每个操作只刷一帧屏，无动画、无渐变、无分割线；阅读页静止时 CPU ≈ 0
- **本地、离线、无权限**：不联网、不要存储权限，书库通过系统文件选择器授权，只读不改
- **文字 / 漫画自动判定**：一页一图的 EPUB 自动进入漫画模式，直接解码图片，不经 WebView

## 下载安装

到 [Releases](../../releases) 下载 `app-release.apk`，用 `adb install` 或设备上的文件管理器安装。

要求 **Android 11（API 30）及以上**。目前只在墨案 Pantone 6（Android 14，6"，1072×1448）上验证过；
其他墨水屏能装能跑，但刷新效果和布局未实测，欢迎反馈。

## 使用

1. 把 EPUB 按文件夹整理好放进设备（例如 `/sdcard/Books/`）：
   - 根目录下的 `.epub` → 书架上的单本
   - 根目录下的每个子文件夹 → 书架上的一套（内部所有 `.epub`，任意深度）
   - 文件夹里的 `cover.jpg` / `cover.png` 作为这一套的封面；没有就取第一本的封面
2. 打开书巢，进设置选「书库文件夹」，扫描完成即出书架（300 本约 9s，之后增量重扫约 1s）
3. 书架 4×3 一屏，上下滑或点页脚翻屏；第一屏再下拉出现搜索 / 设置

阅读时：

- 点屏幕左右两侧 1/3 都是下一页（换手拿也顺手），右滑上一页，点中间呼出工具条；音量键、翻页键可用
- 工具条顶栏可切「文字 / 原版 / 漫画」三种模式，按书记住：
  - **文字**：原生排版引擎，快，只认结构不认出版商 CSS
  - **原版**：Readium（WebView），慢，但还原出版商版式；竖排、内嵌字体、复杂 CSS 的书用它
  - **漫画**：直读图片
- 字号 / 行距 / 边距为滑块；自定义字体在字体面板末项授权一个字体文件夹（默认落在 `/sdcard/Fonts`）
- 页脚：左侧系统时间，右侧全书页码（文字书为估算，随阅读逐步校准）

## 构建

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # 或任意 JDK 17
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

需要 `local.properties` 指向 Android SDK。根目录有 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）时用它签名，
没有则退回 debug 签名——自己构建的包和 Releases 里的包签名不同，不能互相覆盖安装。

单元测试：`./gradlew :app:testDebugUnitTest`。合成测试书库：`python3 tools/gen_test_library.py`。

## 技术

Kotlin + Jetpack Compose，Room，Coil；EPUB 由自写的 ZIP / OPF 解析器读取，文字排版走 `StaticLayout`，
「原版」模式使用 [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) 3.3。
产品边界见 [SPEC.md](SPEC.md)，实现取舍见 [CLAUDE.md](CLAUDE.md)。

## 已知限制

- 只支持 EPUB
- 文字模式没有字间两端对齐（API 34 尚无），中文行尾会有不足一字的参差；表格压成逐行文字
- 屏幕锁定竖屏
- 残影 / 整页刷新交给系统，app 内不提供设置
