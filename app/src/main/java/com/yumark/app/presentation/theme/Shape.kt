package com.yumark.app.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 应用圆角 token。
 *
 * 在此之前 [androidx.compose.material3.MaterialTheme] 根本没收到 shapes，走的是 M3 基线
 * （4/8/12/16/28dp），于是「圆角」这件事在代码里没有唯一答案：AI 界面按 `AiDesign` 的
 * 14dp 画卡片，别处零散写着 `RoundedCornerShape(8.dp)`、`RoundedCornerShape(20.dp)`，
 * 而 Card / Button / TextField / AlertDialog 这些不接 shape 参数的组件又各自拿基线值。
 * 同一屏上因此能同时出现 8、12、14、16、20 五种圆角。
 *
 * 下面的取值不是重新设计，而是把应用**已经在用**的那几个数收进 token：
 * medium 取 14dp 对齐 `AiDesign.CardCorner`，large 取 20dp 对齐侧栏/工作表既有写法。
 * 换句话说这一步不改观感，只是让「改圆角」从此有唯一入口。
 */
object AppShapes {
    /** 徽章、状态药丸、代码内联块。比基线的 4dp 稍圆，与 8dp 那批小控件过渡自然。 */
    val ExtraSmall = 6.dp

    /** 输入框、小按钮、下拉菜单项。 */
    val Small = 10.dp

    /** 卡片、列表行、操作卡——与 `AiDesign.CardCorner` 同值，AI 界面与文件列表因此同语言。 */
    val Medium = 14.dp

    /** 底部工作表、抽屉、大容器——与既有 `RoundedCornerShape(20.dp)` 同值。 */
    val Large = 20.dp

    /** 对话框。保持 M3 基线的 28dp：这一档 Material 规范给得准，没有理由动。 */
    val ExtraLarge = 28.dp
}

/**
 * 传给 `MaterialTheme(shapes = …)` 的实例。
 *
 * 注意 M3 组件对这五档的取用是**固定映射**（Card→medium、AlertDialog→extraLarge、
 * TextField→extraSmall 的上半等），不是由调用点选的。所以改这里会一次性影响全应用，
 * 这正是要的效果；单个组件要偏离时仍可在调用点显式传 shape。
 */
val AppShapeScheme = Shapes(
    extraSmall = RoundedCornerShape(AppShapes.ExtraSmall),
    small = RoundedCornerShape(AppShapes.Small),
    medium = RoundedCornerShape(AppShapes.Medium),
    large = RoundedCornerShape(AppShapes.Large),
    extraLarge = RoundedCornerShape(AppShapes.ExtraLarge)
)
