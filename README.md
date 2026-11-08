# 书架 · Book

一个本地阅读器的可点击原型：立体书架、笔记、书库、我的四个页面，浅色与深色主题，「优雅 / 快速」两档动画速率。适配手机（竖屏）和平板（横竖屏，侧边导航）。

## 下载

最新安卓安装包：<https://github.com/dhhd1426-ops/book/releases/latest/download/book.apk>

在手机上打开链接下载，安装时按提示允许「安装未知来源应用」。新版本可以直接覆盖安装。

## 结构

- `app/src/main/assets/index.html`：整个原型界面（HTML / CSS / JS，一个文件）
- `app/src/main/java/app/book/shelf/MainActivity.java`：安卓外壳，用 WebView 加载上面的页面，处理返回键和文件选择
- `.github/workflows/build.yml`：编译 APK 并发布到 Releases，只在手动运行时触发（Actions → Build APK → Run workflow）

## 说明

- 书目、封面、笔记和阅读时长都是示例数据，封面为程序生成的原创插画。
- `app/proto.keystore` 是原型专用签名，故意提交在仓库里，方便覆盖安装；正式上架前需换成私有签名。
