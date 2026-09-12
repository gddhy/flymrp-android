# flymrp-android

安卓端 [flymrp](https://flymrp.gddhy.net/) 的壳：一个全屏 WebView，把网页版 MRP 模拟器包装成原生 App。

## 特性

- 全屏 WebView，无 ActionBar；`flymrp.gddhy.net` 站内链接在 WebView 打开，外域链接跳系统浏览器
- 返回键：可回退时 `goBack()`，否则双击退出
- 硬件加速
- 图标为纯矢量（XML），APK 内不含 PNG
- **主题跟随网页**：读取页面 `data-theme` / 顶栏底栏实际配色，同步状态栏、导航栏、加载动画，并把取到的配色持久化，冷启动不闪白屏
- **.mrp 文件关联**：可从文件管理器直接打开 mrp 文件，读取后注入页面 `#file` 输入框并派发 `change`，等价于在网页里选择本地文件运行
- **选择文件 / 下载走 SAF**：网页内 `<input type="file">` 与 blob 下载（`canvas.toBlob` 截图等）都通过系统文件选择器完成，不申请任何存储权限
- **网页全屏适配**：`onShowCustomView` 接管 Fullscreen API，隐藏状态栏/导航栏并锁定当前方向
- **按键机适配**：拨号键在普通页面被消费（不拉起拨号）；在 `main.html` 下切换游戏画面全屏（隐藏标题栏与虚拟键盘、画布等比贴边）
- 拨号键全屏下：菜单键 → 模拟器左软键（17），返回键 → 右软键（18），按下与抬起都转发

## 构建

纯 Android 原生组件，**不依赖 AndroidX / Support 库**，仅申请 `INTERNET` 权限。

| 项 | 值 |
| --- | --- |
| 包名 | `net.gddhy.flymrp` |
| minSdk / targetSdk | 24 / 32 |
| AGP / Gradle | 7.3.1 / 7.5.1 |
| compileSdk / buildTools | 32 / 32.0.0 |

```bash
./gradlew assembleRelease
```

`versionName` 固定为 `0.0.3`（与线上 flymrp 构建版本一致），每次调整只递增 `versionCode`。

## 关于上游

网页端模拟器来自 <https://github.com/zixing131/flymrp>（AGPL-3.0）。本仓库**不包含**上游任何代码，运行时通过网络加载 <https://flymrp.gddhy.net/>。

## 关于图标

应用图标来自字节跳动开源图标库 [IconPark](https://iconpark.oceanengine.com/home)（<https://github.com/bytedance/IconPark>，Apache-2.0）。
原始 SVG 见 [icon.svg](./icon.svg)，已按 Android 矢量图格式转写为 `drawable/ic_launcher.xml` 与自适应图标前景 `drawable/ic_launcher_foreground.xml`。

## 许可

本项目采用与上游相同的 **GNU Affero General Public License v3.0 (AGPL-3.0)**，详见 [LICENSE](./LICENSE)。

分发本软件（包括提供编译好的 APK）时，需同时提供对应源码。
