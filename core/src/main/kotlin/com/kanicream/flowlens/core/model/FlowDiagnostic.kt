package com.kanicream.flowlens.core.model

enum class FlowDiagnosticSeverity { INFO, WARNING, ERROR }

/**
 * A concise analysis diagnostic. Never contains source text; [messageKey] is a
 * bundle key resolved by the UI layer, [detail] carries only technical metadata
 * such as analyzer ids or counts (IMPLEMENTATION_GUARDRAILS.md section 13).
 */
data class FlowDiagnostic(
    val severity: FlowDiagnosticSeverity,
    val messageKey: String,
    val detail: String? = null,
)
