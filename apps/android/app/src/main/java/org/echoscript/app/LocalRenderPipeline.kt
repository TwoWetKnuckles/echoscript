package org.echoscript.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Replays from the saved base, so disabling a stage never compounds the last render. */
internal class LocalRenderPipeline(private val context: Context, private val vault: EvidenceVault, private val workflow: OfflineWorkflow) {
    fun render(manifest: JSONObject, actor: String, baseId: String, regions: List<RenderSettings>, clearVoice: ClearVoicePlacement?,
        cancelled: () -> Boolean, progress: (Long) -> Unit): JSONObject {
        require(regions.size in 1..32) { "Use at most 32 processing regions per render" }
        vault.selectRevision(manifest, actor, baseId)
        if (clearVoice == ClearVoicePlacement.BEFORE) {
            // This intermediate revision contains the neural pass only.
            val neuralOnly = JSONObject().put("baseId", baseId).put("rack", EffectRack().json())
                .put("regions", JSONArray()).put("limit", false)
            ClearVoiceNative(context, vault, workflow).enhance(manifest, actor, clearVoice.name, cancelled, progress,
                withClearVoiceRecipe(neuralOnly.toString(), clearVoice, true))
        }
        for ((index, settings) in regions.withIndex()) {
            if (cancelled()) throw InterruptedException("Render cancelled; completed revisions remain in history")
            val plan = withClearVoiceRecipe(completedRegionPlan(settings.workbenchPlan, index + 1), clearVoice, clearVoice == ClearVoicePlacement.BEFORE)
            workflow.enhance(manifest, actor, settings.copy(workbenchPlan = plan), cancelled, progress)
        }
        if (clearVoice == ClearVoicePlacement.AFTER) {
            val plan = manifest.activeAudio().operationParameters().optJSONObject("workbenchPlan")?.toString()
            ClearVoiceNative(context, vault, workflow).enhance(manifest, actor, clearVoice.name, cancelled, progress,
                withClearVoiceRecipe(plan, clearVoice, true))
        }
        return manifest.activeAudio()
    }
}

internal fun withClearVoiceRecipe(plan: String?, placement: ClearVoicePlacement?, applied: Boolean): String? {
    if (plan == null) return null
    return JSONObject(plan).put("clearVoice", JSONObject().put("enabled", placement != null)
        .put("placement", (placement ?: ClearVoicePlacement.AFTER).name).put("applied", applied)
        .put("model", CLEARVOICE_MODEL).put("modelSha256", CLEARVOICE_ONNX_HASH)).toString()
}

internal fun clearVoiceFromRecipe(plan: JSONObject?): ClearVoicePlacement? {
    val stage = plan?.optJSONObject("clearVoice") ?: return null
    if (!stage.getBoolean("enabled")) return null
    require(stage.getString("model") == CLEARVOICE_MODEL && stage.getString("modelSha256") == CLEARVOICE_ONNX_HASH) { "This recipe requires a different ClearVoice model" }
    return ClearVoicePlacement.valueOf(stage.getString("placement"))
}
