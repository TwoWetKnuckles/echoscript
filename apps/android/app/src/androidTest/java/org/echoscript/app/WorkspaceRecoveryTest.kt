package org.echoscript.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class WorkspaceRecoveryTest {
    @Test fun reopeningReportsAnInterruptedTaskAndKeepsCompletedEvidence() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val vault = EvidenceVault(application)
        val bytes = "SYNTHETIC RECOVERY TEST DATA NOT EVIDENCE".toByteArray()
        val case = vault.importSource(bytes.inputStream(), "synthetic-recovery.txt", "Synthetic recovery", "TEST")
        val preferences = application.getSharedPreferences("echoscript_workspace", Context.MODE_PRIVATE)
        check(preferences.edit().putString("pendingJob", "Rendering audio revision").commit())
        try {
            val model = VaultModel(application)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!model.state.value.ready && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(model.state.value.ready)
            assertTrue(model.state.value.recoveryWarnings.any { it.startsWith("Interrupted task") })
            assertEquals(EvidenceVault.sha(bytes), case.activeAudio().getString("sha256"))
            assertTrue(vault.verify(case).contains("agree"))
        } finally {
            check(preferences.edit().remove("pendingJob").commit())
            java.io.File(application.filesDir, "EvidenceVault/cases/${case.getString("caseId")}").deleteRecursively()
        }
    }
}
