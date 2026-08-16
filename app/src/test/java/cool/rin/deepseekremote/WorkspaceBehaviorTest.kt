package cool.rin.deepseekremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBehaviorTest {
    private val root = HarnessApi.Workspace("root-id", "root", "/root", emptyList())
    private val project = HarnessApi.Workspace("project-id", "project", "/root/project/", listOf("listed"))

    @Test
    fun `current workspace is resolved by session membership first`() {
        val session = HarnessApi.Session("listed", null, "/somewhere/else", null, 0, false, false)

        assertEquals(project, WorkspaceBehavior.matchingWorkspace(session, listOf(root, project)))
    }

    @Test
    fun `current workspace is resolved by normalized cwd`() {
        val session = HarnessApi.Session("session", null, "/root/project", null, 0, false, false)

        assertEquals(project, WorkspaceBehavior.matchingWorkspace(session, listOf(root, project)))
        assertTrue(WorkspaceBehavior.samePath("/root/project/", "/root/project"))
    }

    @Test
    fun `ungrouped session creates without a workspace id`() {
        val session = HarnessApi.Session("session", null, "/opt/unregistered", null, 0, false, false)

        assertNull(WorkspaceBehavior.matchingWorkspace(session, listOf(root, project)))
    }
}
