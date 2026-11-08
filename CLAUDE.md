# 项目约定

- **不要打包软件**：除非用户明确要求“打包”，否则不要编译 APK、不要运行 Build APK 工作流、不要发布 Release。推送代码不会触发打包。
- 界面源文件是 `app/src/main/assets/index.html`（单文件原型）；需同时适配手机竖屏和平板（≥600px 宽，侧边导航）。

## 一开始就写对：防止“一个页面的东西跑到另一个页面”

这类问题出过四次（`.sub`、`.pb`、`.vol` 重名；书库轮播 `visibility:visible` 透到笔记页）。写代码时直接遵守：

1. **不显示的页面不渲染**：`.view:not(.is-on){display:none}`，不要改回只用 `visibility` 隐藏。
2. **隐藏层里的子元素不写 `visibility:visible`**。轮播、弹层里的显隐用 `opacity` + `pointer-events`，或 `display`。
3. **新模块的类名加前缀**（如 `rd-` 阅读、`toc-` 目录、`src-` 书源、`disc-` 发现），不要新建 `.top`、`.sub`、`.vol`、`.pb` 这类通用短名；新加之前先搜一下这个名字是否已被使用。
4. 新加 CSS 用 `/* ---------- 名称 ---------- */` 分段。

## 检查工具（不必每次跑）

- `python3 tools/check_css.py`：样式类重名，一秒内完成。新增样式时顺手跑。
- `node tools/smoke.js`：手机 / 平板、浅色 / 深色走遍各页面，约几分钟。只在**打包前**或用户要求时跑。
