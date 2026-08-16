package cool.rin.deepseekremote

internal enum class AppThemePreference(val storedValue: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        fun fromStored(value: String?): AppThemePreference = entries.firstOrNull {
            it.storedValue == value
        } ?: DARK
    }
}

internal fun AppThemePreference.resolvesDark(systemDark: Boolean): Boolean = when (this) {
    AppThemePreference.LIGHT -> false
    AppThemePreference.DARK -> true
    AppThemePreference.SYSTEM -> systemDark
}

internal data class AppPalette(
    val surface: Int,
    val drawer: Int,
    val drawerButton: Int,
    val drawerBorder: Int,
    val drawerSelected: Int,
    val drawerPrimary: Int,
    val drawerSecondary: Int,
    val drawerTertiary: Int,
    val drawerBlue: Int,
    val composer: Int,
    val codeSurface: Int,
    val control: Int,
    val controlText: Int,
    val webSettings: Int,
    val webSettingsBorder: Int,
    val selected: Int,
    val userBubble: Int,
    val text: Int,
    val muted: Int,
    val border: Int,
    val borderSubtle: Int,
    val tool: Int,
    val todoPanel: Int,
    val todoBorder: Int,
    val menu: Int,
    val menuSelected: Int,
    val notice: Int,
    val inlineCode: Int,
    val green: Int,
    val blue: Int,
    val sendDisabled: Int,
    val sendDisabledIcon: Int,
    val activity: Int,
    val amber: Int,
    val approvalStrip: Int,
    val red: Int,
    val primaryButtonFill: Int,
    val primaryButtonText: Int,
)

internal object AppPalettes {
    val DARK = AppPalette(
        surface = color(0, 0, 0),
        drawer = color(27, 27, 28),
        drawerButton = color(67, 69, 74),
        drawerBorder = color(90, 91, 95),
        drawerSelected = color(45, 45, 46),
        drawerPrimary = color(249, 250, 251),
        drawerSecondary = color(207, 211, 214),
        drawerTertiary = color(173, 178, 184),
        drawerBlue = color(103, 158, 254),
        composer = color(33, 33, 33),
        codeSurface = color(24, 24, 26),
        control = color(28, 28, 28),
        controlText = color(214, 214, 214),
        webSettings = color(46, 46, 49),
        webSettingsBorder = color(62, 62, 65),
        selected = color(38, 38, 38),
        userBubble = color(47, 47, 47),
        text = color(244, 244, 244),
        muted = color(171, 171, 171),
        border = color(76, 76, 76),
        borderSubtle = color(62, 62, 62),
        tool = color(30, 30, 30),
        todoPanel = color(54, 54, 56),
        todoBorder = color(72, 72, 75),
        menu = color(55, 55, 57),
        menuSelected = color(73, 73, 76),
        notice = color(63, 48, 20),
        inlineCode = color(38, 38, 38),
        green = color(91, 207, 139),
        blue = color(82, 139, 255),
        sendDisabled = color(55, 68, 94),
        sendDisabledIcon = color(145, 148, 154),
        activity = color(190, 193, 199),
        amber = color(251, 191, 36),
        approvalStrip = color(39, 35, 24),
        red = color(248, 113, 113),
        primaryButtonFill = color(246, 246, 246),
        primaryButtonText = color(24, 24, 24),
    )

    // Harness Web light tokens: white base/layers, bluish neutral hierarchy,
    // pale blue user bubble, and a near-black primary action.
    val LIGHT = AppPalette(
        surface = color(255, 255, 255),
        drawer = color(249, 250, 251),
        drawerButton = color(235, 238, 242),
        drawerBorder = color(207, 211, 214),
        drawerSelected = color(235, 238, 242),
        drawerPrimary = color(15, 17, 21),
        drawerSecondary = color(97, 102, 107),
        drawerTertiary = color(129, 133, 140),
        drawerBlue = color(65, 118, 230),
        composer = color(255, 255, 255),
        codeSurface = color(249, 250, 251),
        control = color(245, 246, 247),
        controlText = color(53, 54, 56),
        webSettings = color(255, 255, 255),
        webSettingsBorder = color(225, 229, 238),
        selected = color(245, 246, 247),
        userBubble = color(237, 243, 254),
        text = color(15, 17, 21),
        muted = color(97, 102, 107),
        border = color(207, 211, 214),
        borderSubtle = color(235, 238, 242),
        tool = color(249, 250, 251),
        todoPanel = color(245, 246, 247),
        todoBorder = color(225, 229, 238),
        menu = color(255, 255, 255),
        menuSelected = color(245, 246, 247),
        notice = color(254, 245, 231),
        inlineCode = color(235, 238, 242),
        green = color(34, 197, 94),
        blue = color(65, 118, 230),
        sendDisabled = color(235, 238, 242),
        sendDisabledIcon = color(151, 157, 166),
        activity = color(97, 102, 107),
        amber = color(245, 158, 11),
        approvalStrip = color(254, 245, 231),
        red = color(236, 19, 19),
        primaryButtonFill = color(15, 17, 21),
        primaryButtonText = color(255, 255, 255),
    )

    private fun color(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
