package cool.rin.deepseekremote

internal object WorkspaceBehavior {
    fun matchingWorkspace(
        session: HarnessApi.Session?,
        workspaces: List<HarnessApi.Workspace>,
    ): HarnessApi.Workspace? {
        session ?: return null
        return workspaces.firstOrNull { workspace ->
            session.id in workspace.sessionIds || samePath(session.cwd, workspace.path)
        }
    }

    fun samePath(first: String?, second: String?): Boolean {
        fun normalized(value: String?) = value?.trim()?.trimEnd('/')?.ifBlank { "/" }
        return normalized(first) != null && normalized(first) == normalized(second)
    }
}
