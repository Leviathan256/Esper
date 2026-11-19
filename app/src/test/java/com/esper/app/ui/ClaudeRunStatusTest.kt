package com.esper.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import com.esper.app.core.WorkflowRun
import org.junit.Assert.assertEquals
import org.junit.Test

/** A run card says its status with an icon and a word, never colour alone (docs/VISUALS.md §4.6). */
class ClaudeRunStatusTest {
    private fun run(status: String, conclusion: String?) =
        WorkflowRun(id = 1, name = "Claude run", status = status, conclusion = conclusion, createdAt = "2026-11-19", htmlUrl = "")

    @Test
    fun `each run status has its own icon`() {
        assertEquals(Icons.Filled.Schedule, runIcon(run("queued", null)))
        assertEquals(Icons.Filled.Schedule, runIcon(run("in_progress", null)))
        assertEquals(Icons.Filled.CheckCircle, runIcon(run("completed", "success")))
        assertEquals(Icons.Filled.ErrorOutline, runIcon(run("completed", "failure")))
        assertEquals(Icons.Filled.ErrorOutline, runIcon(run("completed", "cancelled")))
    }
}
