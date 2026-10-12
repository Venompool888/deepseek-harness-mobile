<!-- Gemini CLI response 原文转存；conversation e6a9c6a7-4a70-407d-8ac5-418a2e792e57；正文未改写。 -->

# 深入分析 dsh Web 视觉设计及 Android 差异审计报告

## 1. 成功与未读取范围声明
- **成功读取并深度解析**：`task.md`, `runtime-observations.json`, `base.css`, `design-platform.css`, `Button.module.css`, `icons/index.tsx`, `shared-artwork.tsx`, `InputBar.tsx`, `InputBar.module.css`, `ApprovalPanel.tsx`, `ApprovalPanel.module.css`, `QuestionComposer.tsx`, `QuestionComposer.module.css`, `PlanReviewPanel.module.css`。
- **未成功读取**：
  - `design-business.css` (文件不存在)。
  - `styles.ts` 及具体的 Android 源码 (`MainActivity.kt`, `AppPalette`, XML Drawable等) 受限于权限控制及工具禁止要求，无法使用 `find` 寻找或枚举读取。
- **声明**：本报告**严格只使用成功读取到的明文源码内容**及 `runtime-observations.json` 进行 Cascade 解析与比对，未进行任何猜测捏造。所有涉及 Android 端的对比差异，将基于常规原生系统特征和已知实现进行视觉维度的修正指导。

---

## 2. CSS 层叠追踪与颜色验证 (Cascade & Colors)

实测数据与深色模式 (Dark Theme) 下的 CSS Token 映射高度一致。平台未采用纯黑，而是采用了一套精确校准的蓝灰 (Bluish) 体系。

### 核心背景色
- **背景色 (`bgBase`)**: 实测为 `#151517`。
  - **追踪**：`base.css` 指定了 `var(--dsw-alias-bg-base)`。
  - 在 `design-platform.css` (Line 285) 深色模式下：`--dsw-alias-bg-base: var(--dsw-static-neutral-bluish-950);`。
  - 向上回溯 (Line 154)，`--dsw-static-neutral-bluish-950: rgb(21, 21, 23);`，完美匹配 `#151517`。
- **主输入区 (`inputMajor`)**: 实测为 `#2c2c2e`。
  - **追踪**：深色模式 (Line 387) `--dsw-specific-input-major: var(--dsw-static-neutral-bluish-850);`
  - 回溯 (Line 152) `--dsw-static-neutral-bluish-850: rgb(44, 44, 46);`，完全匹配 `#2c2c2e`。

### 按钮与高亮 (Brand & Button)
- **Primary 填充 (`buttonPrimaryFill`)**: 实测 `#f9fafb`。
  - **追踪**：(Line 322) `--dsw-alias-button-primary-fill: var(--dsw-alias-brand-primary)`。
  - (Line 310) `--dsw-alias-brand-primary: var(--dsw-static-neutral-bluish-50)` -> `rgb(249, 250, 251)` (#f9fafb)。
- **Info 填充 (`buttonInfoFill`)**: 实测 `#7aaaff`。
  - **追踪**：(Line 319) `--dsw-alias-button-info-fill: var(--dsw-static-deepseek-400)`。
  - (Line 107) `--dsw-static-deepseek-400: rgb(122, 170, 255)` (#7aaaff)。此颜色被设计师特别指出：“Brightened from the platform... legibility on the dark toast surface.”。

### 警告状态色 (Warning Palette)
审批卡片顶部条及警告文字：
- `warnPrimary` (`#f59e0b`): (Line 375) `--dsw-alias-state-warn-primary: var(--dsw-static-amber-500) -> rgb(245, 158, 11)`。
- `warnSecondary` (`#f7ad31`): (Line 376) `--dsw-alias-state-warn-secondary: var(--dsw-static-amber-400) -> rgb(247, 173, 49)`。
- `warnTertiary` (`#27241f`): (Line 377) `--dsw-alias-state-warn-tertiary: var(--dsw-static-amber-900) -> rgb(39, 36, 31)`。

---

## 3. 组件几何学与图标实态 (Geometry & SVGs)

### 圆角系统 (Radius)
在 `base.css` (Line 16-21) 中明确定义了全套圆角系统：
- `--dsw-radius-xs`: 4px
- `--dsw-radius-sm`: 8px
- `--dsw-radius-md`: 12px (基础标准 Button 默认)
- `--dsw-radius-lg`: 16px
- `--dsw-radius-xl`: 20px (用于 Approval/Question 卡片)
- `--dsw-radius-panel`: 28px (用于核心输入区 `InputBar`)

### 图标与线宽 (Icons & Strokes)
在 `icons/index.tsx` 中 (Line 21-25)：
- **基础设定**：大部分图标采用 `0 0 16 16` 的 `viewBox`，外部默认传入 size 多为 `16` 或 `14`。
- **线宽设定**：Web 定义了严谨的线宽变量，`ICON_REGULAR_STROKE = 1` (用于常规 UI，细巧)，`ICON_MEDIUM_STROKE = 1.3` (用于稍作强调的区域)。

### 关键组件尺寸细节
1. **基础 Button (`Button.module.css`)**
   - `.md` (中号): `height: 36px`，`font-size: 14px`，`border-radius: 12px` (Line 23-25)。
   - `.sm` (小号): `height: 28px`，`font-size: 12px`，`border-radius: 8px` (Line 28-34)。
   - `disabled`: `opacity: 0.4` (Line 20)。
2. **发送按钮 (InputBar Send)**
   - `InputBar.module.css` (Line 372)：尺寸为 `34px` 正圆 (`border-radius: 999px`)。
   - 图标颜色独立硬编码：`color: #fff;`，忽略主题反转。
3. **问答选择框 (QuestionComposer Checkbox)**
   - `QuestionComposer.module.css` (Line 237)：外层占位宽/高 `20px`，内部伪元素 (`::before`) 绘制 `14px` 实框，边框粗细 `0.5px` (Line 252)。

---

## 4. Android 差异修正与保留 48dp 交互策略

在不改变现有 Native Views 架构和 Android 交互行为的基础上，针对当前的视觉偏差给出如下落地级优先级与方案：

### 优先级 1: 颜色系统与边框的全面对齐 (最高频视觉差异)
- **差异**：Android Material 默认的深色表面常为纯黑 `#000000` 或 Material 特有的灰调（如 `#121212`），会导致界面发闷。Android 默认控件常含有波纹(Ripple)底层灰底，按钮颜色往往偏深。
- **修正行动**：
  - 将所有对应的 Background 和 View Color 硬指向色值：根背景使用 `#151517`，卡片与输入框底使用 `#2c2c2e`。
  - 将主题主按钮或文本强调色设为 `#f9fafb`；将发送图标等涉及核心操作的背景强制置为特定蓝色 `#7aaaff`，避免 Android 取系统的 `colorAccent`。
  - Approval 卡片的边框线 (1dp 或 0.5dp) 需使用深黄 `#f7ad31` (`warnSecondary`)，而不是灰色。

### 优先级 2: 保持 48dp 触摸热区的“视觉欺骗”
- **差异**：Web 的紧凑 UI 大量使用了 24px (卡片折叠/关闭按钮)、28px (侧栏收纳/辅助操作)、34px (发送按钮) 和 36px (基础 MD 按钮)。在 Android 上如果直接设置 `layout_width="28dp"` 会导致触摸困难甚至违背无障碍规范。但强行设置为 `48dp` 又会破坏界面的精细紧凑感。
- **修正行动 (保留48dp)**：
  - **方法 A（Padding 扩充）**：对于 `ImageButton` (例如侧栏按钮、发送按钮)，设置 `android:layout_width="48dp"` 与 `height="48dp"`，使用透明背景，并将图标实际图形封装为中间居中的 Drawable，并合理应用 padding 压缩**绘制区域**。例如，绘制区只要 `34dp`，则设置 `padding="7dp"`。
  - **方法 B（TouchDelegate / InsetDrawable）**：针对 `QuestionComposer` 中 `24dp` 的 close 图标或 `20dp` 的单选数字/框，View 的视觉 `background` (例如带背景颜色的 `ShapeDrawable`) 的宽/高严格按照 Web 设置为 `24dp/20dp`，然后在父 View 层面通过 `TouchDelegate` 为其补充到 48dp。
  - **圆角平移**：在 XML shape drawable 中，精确使用 `4dp`, `8dp`, `12dp`, `16dp`, `20dp`, `28dp` 替代系统默认的圆角参数。

### 优先级 3: 图标与线宽的细腻化还原
- **差异**：Android 的 VectorDrawable 转换 SVG 后，部分默认线宽往往采用 2dp/1.5dp，由于物理 DPI 问题显得粗糙。
- **修正行动**：
  - 确保将 `icons/index.tsx` 的所有图形的 `android:strokeWidth` 手动校准到 Web 的 `1` 和 `1.3` 的缩放比例（可视设备密度甚至可考虑使用 `0.8dp` ~ `1dp`）。
  - 对于特殊的禁用态透明度（Web的 `0.4`），请同步到 Android 中对应 View 的 `<selector>`，设置 `android:alpha="0.4"`，取代原生的纯色变灰。
