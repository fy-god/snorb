package com.macropilot.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.macropilot.app.BuildConfig
import com.macropilot.app.automation.ActionOutcome
import com.macropilot.app.automation.DeviceActions
import com.macropilot.app.automation.MacroPilotAccessibilityService
import com.macropilot.app.factory.PromotionGate
import com.macropilot.app.factory.SkillTestRunner
import com.macropilot.app.model.SkillCandidate
import com.macropilot.app.runtime.MacroExecutor
import com.macropilot.app.runtime.ArchitectureSelfCheck
import com.macropilot.app.runtime.CapabilityProfiler
import com.macropilot.app.runtime.NodeInspector
import com.macropilot.app.store.AppUiModuleStore
import com.macropilot.app.store.BuiltinKnowledgeSeeder
import com.macropilot.app.store.CoordinateCalibrationStore
import com.macropilot.app.store.LocalDataGovernanceStore
import com.macropilot.app.store.MacroTemplateStore
import com.macropilot.app.store.RunLogStore
import com.macropilot.app.store.SkillTestReportStore
import com.macropilot.app.store.TrainingSampleStore

class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "Ignoring debug command in non-debug build")
            return
        }

        when (intent.action) {
            ACTION_SAVE_SAMPLE -> saveSample(context)
            ACTION_SEED_BUILTINS -> seedBuiltins(context)
            ACTION_INVENTORY -> logInventory(context)
            ACTION_DELETE_TRAINING_SAMPLES -> deleteTrainingSamples(context)
            ACTION_DELETE_FACTORY_ARTIFACTS -> deleteFactoryArtifacts(context)
            ACTION_COMPACT_FACTORY_ARTIFACTS -> compactFactoryArtifacts(context)
            ACTION_DELETE_RUN_LOGS -> deleteRunLogs(context)
            ACTION_SELF_CHECK -> runSelfCheck()
            ACTION_EXPORT_PREVIEW -> exportPreview(context)
            ACTION_EXPORT_TRAINING_SAMPLES -> exportTrainingSamples(context)
            ACTION_DELETE_EXPORTS -> deleteExports(context)
            ACTION_DELETE_COORDINATE_CANDIDATES -> deleteCoordinateCandidates(context)
            ACTION_SAVE_COORDINATE_CANDIDATE -> saveCoordinateCandidate(context)
            ACTION_RUN_SETTINGS_SEARCH -> {
                val pending = goAsync()
                Thread {
                    try {
                        runSettingsSearch(context, intent.getStringExtra("query") ?: "WLAN")
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            ACTION_RUN_SETTINGS_TESTS -> {
                val pending = goAsync()
                Thread {
                    try {
                        runSettingsTests(context, intent.getIntExtra("count", 5).coerceIn(1, 20))
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            ACTION_RUN_SETTINGS_RECOVERY_TEST -> {
                val pending = goAsync()
                Thread {
                    try {
                        runSettingsRecoveryTest(context)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            ACTION_RUN_SETTINGS_SCROLL_TEST -> {
                val pending = goAsync()
                Thread {
                    try {
                        runSettingsScrollTest(context, intent.getStringExtra("target") ?: "系统")
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            ACTION_RUN_SETTINGS_SELECT_TEST -> {
                val pending = goAsync()
                Thread {
                    try {
                        runSettingsSelectTest(context, intent.getStringExtra("item") ?: "WLAN")
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    private fun saveSample(context: Context) {
        val serviceConnected = MacroPilotAccessibilityService.instance != null
        val state = NodeInspector(context).currentState()
        val profile = CapabilityProfiler().profile(state, serviceConnected)
        if (state == null) {
            Log.w(TAG, "DEBUG_SAVE_SAMPLE failed: ${profile.reason}")
            return
        }
        val file = TrainingSampleStore(context).saveSnapshot(
            state = state,
            profile = profile,
            note = "debug_broadcast_snapshot"
        )
        Log.i(TAG, "DEBUG_SAVE_SAMPLE saved ${file.absolutePath}")
    }

    private fun seedBuiltins(context: Context) {
        val result = BuiltinKnowledgeSeeder(context).seedSettingsSearch()
        Log.i(TAG, "DEBUG_SEED_BUILTINS saved ${result.modulePath} and ${result.templatePath}")
    }

    private fun logInventory(context: Context) {
        val inventory = LocalDataGovernanceStore(context).inventory()
        Log.i(TAG, "DEBUG_INVENTORY ${inventory.compact()}")
    }

    private fun deleteTrainingSamples(context: Context) {
        val deleted = LocalDataGovernanceStore(context).deleteTrainingSamples()
        Log.i(TAG, "DEBUG_DELETE_TRAINING_SAMPLES deleted=$deleted")
    }

    private fun deleteFactoryArtifacts(context: Context) {
        val deleted = LocalDataGovernanceStore(context).deleteFactoryArtifacts()
        Log.i(TAG, "DEBUG_DELETE_FACTORY_ARTIFACTS deleted=$deleted")
    }

    private fun compactFactoryArtifacts(context: Context) {
        val report = LocalDataGovernanceStore(context).compactFactoryArtifacts()
        Log.i(TAG, "DEBUG_COMPACT_FACTORY_ARTIFACTS ${report.compact()}")
    }

    private fun deleteRunLogs(context: Context) {
        val deleted = LocalDataGovernanceStore(context).deleteRunLogs()
        Log.i(TAG, "DEBUG_DELETE_RUN_LOGS deleted=$deleted")
    }

    private fun deleteExports(context: Context) {
        val deleted = LocalDataGovernanceStore(context).deleteExports()
        Log.i(TAG, "DEBUG_DELETE_EXPORTS deleted=$deleted")
    }

    private fun deleteCoordinateCandidates(context: Context) {
        val deleted = LocalDataGovernanceStore(context).deleteCoordinateCandidates()
        Log.i(TAG, "DEBUG_DELETE_COORDINATE_CANDIDATES deleted=$deleted")
    }

    private fun runSelfCheck() {
        val report = ArchitectureSelfCheck().run()
        Log.i(TAG, "DEBUG_SELF_CHECK ${report.compact()}")
        Log.i(TAG, "DEBUG_SELF_CHECK_JSON ${report.toJson()}")
        report.checks.forEach { check ->
            Log.i(TAG, "DEBUG_SELF_CHECK_ITEM ${check.name} passed=${check.passed} detail=${check.detail}")
        }
    }

    private fun exportPreview(context: Context) {
        val preview = LocalDataGovernanceStore(context).trainingExportPreview()
        Log.i(TAG, "DEBUG_EXPORT_PREVIEW ${preview.compact()}")
    }

    private fun exportTrainingSamples(context: Context) {
        val file = LocalDataGovernanceStore(context).exportTrainingSamples()
        Log.i(TAG, "DEBUG_EXPORT_TRAINING_SAMPLES file=${file?.absolutePath ?: "BLOCKED"}")
    }

    private fun saveCoordinateCandidate(context: Context) {
        val state = NodeInspector(context).currentState()
        if (state == null) {
            Log.w(TAG, "DEBUG_SAVE_COORDINATE_CANDIDATE failed: no UiState")
            return
        }
        val file = CoordinateCalibrationStore(context).saveCandidate(state)
        Log.i(TAG, "DEBUG_SAVE_COORDINATE_CANDIDATE file=${file?.absolutePath ?: "NO_CANDIDATE"}")
    }

    private fun runSettingsSearch(context: Context, query: String) {
        seedBuiltins(context)
        val appUi = AppUiModuleStore(context).loadApproved(BuiltinKnowledgeSeeder.SETTINGS_PACKAGE)
        val template = MacroTemplateStore(context).loadApproved(BuiltinKnowledgeSeeder.SETTINGS_SEARCH_TEMPLATE_ID)
        if (appUi == null || template == null) {
            Log.e(TAG, "DEBUG_RUN_SETTINGS_SEARCH failed: approved knowledge missing")
            return
        }
        val startedAt = System.currentTimeMillis()
        val result = MacroExecutor(
            inspector = NodeInspector(context),
            profiler = CapabilityProfiler(),
            actions = DeviceActions(context)
        ).execute(template, appUi, mapOf("query" to query))
        RunLogStore(context).save(
            templateId = template.id,
            result = result,
            durationMs = System.currentTimeMillis() - startedAt,
            note = "debug_settings_search query=$query"
        )
        Log.i(TAG, "DEBUG_RUN_SETTINGS_SEARCH result=${result.status} confidence=${result.confidence} message=${result.message}")
    }

    private fun runSettingsTests(context: Context, count: Int) {
        seedBuiltins(context)
        val appUi = AppUiModuleStore(context).loadApproved(BuiltinKnowledgeSeeder.SETTINGS_PACKAGE)
        val template = MacroTemplateStore(context).loadApproved(BuiltinKnowledgeSeeder.SETTINGS_SEARCH_TEMPLATE_ID)
        if (appUi == null || template == null) {
            Log.e(TAG, "DEBUG_RUN_SETTINGS_TESTS failed: approved knowledge missing")
            return
        }
        val actions = DeviceActions(context)
        val runner = SkillTestRunner(
            MacroExecutor(
                inspector = NodeInspector(context),
                profiler = CapabilityProfiler(),
                actions = actions
            )
        )
        val values = (1..count).map { index -> "MPTEST$index" }
        val report = runner.runRepeated(
            template = template,
            appUi = appUi,
            paramName = "query",
            values = values,
            beforeEach = {
                actions.home()
                sleepQuietly(250L)
            }
        )
        val decision = PromotionGate().evaluate(
            SkillCandidate(
                id = "candidate_${template.id}",
                template = template,
                notes = listOf("debug settings repeated test")
            ),
            report
        )
        val file = SkillTestReportStore(context).save(report, decision)
        Log.i(
            TAG,
            "DEBUG_RUN_SETTINGS_TESTS saved ${file.absolutePath} success=${report.success}/${report.totalRuns} " +
                "p95=${report.p95DurationMs} promotion=${decision.status} approved=${decision.approved} reason=${decision.reason}"
        )
    }

    private fun runSettingsRecoveryTest(context: Context) {
        seedBuiltins(context)
        val appUi = AppUiModuleStore(context).loadApproved(BuiltinKnowledgeSeeder.SETTINGS_PACKAGE)
        val template = MacroTemplateStore(context).loadApproved(BuiltinKnowledgeSeeder.SETTINGS_SEARCH_TEMPLATE_ID)
        if (appUi == null || template == null) {
            Log.e(TAG, "DEBUG_RUN_SETTINGS_RECOVERY_TEST failed: approved knowledge missing")
            return
        }
        val actions = DeviceActions(context)
        val inspector = NodeInspector(context)
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prep = ActionOutcome(true, "ACTION_BLUETOOTH_SETTINGS sent")
        sleepQuietly(600L)
        val startedAt = System.currentTimeMillis()
        val result = MacroExecutor(
            inspector = inspector,
            profiler = CapabilityProfiler(),
            actions = actions
        ).execute(template, appUi, mapOf("query" to "RECOVERY"))
        RunLogStore(context).save(
            templateId = template.id,
            result = result,
            durationMs = System.currentTimeMillis() - startedAt,
            note = "debug_settings_recovery_test prep=${prep.success}:${prep.reason}"
        )
        Log.i(
            TAG,
            "DEBUG_RUN_SETTINGS_RECOVERY_TEST prep=${prep.success}:${prep.reason} " +
                "result=${result.status} confidence=${result.confidence} message=${result.message}"
        )
    }

    private fun runSettingsScrollTest(context: Context, target: String) {
        BuiltinKnowledgeSeeder(context).seedSettingsScrollVisible()
        val appUi = AppUiModuleStore(context).loadApproved(BuiltinKnowledgeSeeder.SETTINGS_PACKAGE)
        val template = MacroTemplateStore(context).loadApproved(BuiltinKnowledgeSeeder.SETTINGS_SCROLL_VISIBLE_TEMPLATE_ID)
        if (appUi == null || template == null) {
            Log.e(TAG, "DEBUG_RUN_SETTINGS_SCROLL_TEST failed: approved knowledge missing")
            return
        }
        val startedAt = System.currentTimeMillis()
        val result = MacroExecutor(
            inspector = NodeInspector(context),
            profiler = CapabilityProfiler(),
            actions = DeviceActions(context)
        ).execute(template, appUi, mapOf("target" to target))
        RunLogStore(context).save(
            templateId = template.id,
            result = result,
            durationMs = System.currentTimeMillis() - startedAt,
            note = "debug_settings_scroll_test target=$target"
        )
        Log.i(
            TAG,
            "DEBUG_RUN_SETTINGS_SCROLL_TEST target=$target result=${result.status} " +
                "confidence=${result.confidence} message=${result.message}"
        )
    }

    private fun runSettingsSelectTest(context: Context, item: String) {
        BuiltinKnowledgeSeeder(context).seedSettingsSelectVisible()
        val appUi = AppUiModuleStore(context).loadApproved(BuiltinKnowledgeSeeder.SETTINGS_PACKAGE)
        val template = MacroTemplateStore(context).loadApproved(BuiltinKnowledgeSeeder.SETTINGS_SELECT_VISIBLE_TEMPLATE_ID)
        if (appUi == null || template == null) {
            Log.e(TAG, "DEBUG_RUN_SETTINGS_SELECT_TEST failed: approved knowledge missing")
            return
        }
        val startedAt = System.currentTimeMillis()
        val result = MacroExecutor(
            inspector = NodeInspector(context),
            profiler = CapabilityProfiler(),
            actions = DeviceActions(context)
        ).execute(template, appUi, mapOf("item" to item))
        RunLogStore(context).save(
            templateId = template.id,
            result = result,
            durationMs = System.currentTimeMillis() - startedAt,
            note = "debug_settings_select_test item=$item"
        )
        Log.i(
            TAG,
            "DEBUG_RUN_SETTINGS_SELECT_TEST item=$item result=${result.status} " +
                "confidence=${result.confidence} message=${result.message}"
        )
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val ACTION_SAVE_SAMPLE = "com.macropilot.app.DEBUG_SAVE_SAMPLE"
        const val ACTION_SEED_BUILTINS = "com.macropilot.app.DEBUG_SEED_BUILTINS"
        const val ACTION_INVENTORY = "com.macropilot.app.DEBUG_INVENTORY"
        const val ACTION_DELETE_TRAINING_SAMPLES = "com.macropilot.app.DEBUG_DELETE_TRAINING_SAMPLES"
        const val ACTION_DELETE_FACTORY_ARTIFACTS = "com.macropilot.app.DEBUG_DELETE_FACTORY_ARTIFACTS"
        const val ACTION_COMPACT_FACTORY_ARTIFACTS = "com.macropilot.app.DEBUG_COMPACT_FACTORY_ARTIFACTS"
        const val ACTION_DELETE_RUN_LOGS = "com.macropilot.app.DEBUG_DELETE_RUN_LOGS"
        const val ACTION_SELF_CHECK = "com.macropilot.app.DEBUG_SELF_CHECK"
        const val ACTION_EXPORT_PREVIEW = "com.macropilot.app.DEBUG_EXPORT_PREVIEW"
        const val ACTION_EXPORT_TRAINING_SAMPLES = "com.macropilot.app.DEBUG_EXPORT_TRAINING_SAMPLES"
        const val ACTION_DELETE_EXPORTS = "com.macropilot.app.DEBUG_DELETE_EXPORTS"
        const val ACTION_DELETE_COORDINATE_CANDIDATES = "com.macropilot.app.DEBUG_DELETE_COORDINATE_CANDIDATES"
        const val ACTION_SAVE_COORDINATE_CANDIDATE = "com.macropilot.app.DEBUG_SAVE_COORDINATE_CANDIDATE"
        const val ACTION_RUN_SETTINGS_SEARCH = "com.macropilot.app.DEBUG_RUN_SETTINGS_SEARCH"
        const val ACTION_RUN_SETTINGS_TESTS = "com.macropilot.app.DEBUG_RUN_SETTINGS_TESTS"
        const val ACTION_RUN_SETTINGS_RECOVERY_TEST = "com.macropilot.app.DEBUG_RUN_SETTINGS_RECOVERY_TEST"
        const val ACTION_RUN_SETTINGS_SCROLL_TEST = "com.macropilot.app.DEBUG_RUN_SETTINGS_SCROLL_TEST"
        const val ACTION_RUN_SETTINGS_SELECT_TEST = "com.macropilot.app.DEBUG_RUN_SETTINGS_SELECT_TEST"
        private const val TAG = "MacroPilotDebug"
    }
}
