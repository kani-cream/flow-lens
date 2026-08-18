package com.kanicream.flowlens.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.kanicream.flowlens.FlowLensBundle

/**
 * Project settings UI: analysis bounds and traversal policy.
 *
 * Bindings read and write through the settings service on every access rather
 * than through a captured state object: `loadState` replaces that object, so a
 * settings import or external change while the dialog is open would otherwise
 * make Apply write into an orphaned instance and silently lose the edit.
 */
class FlowLensConfigurable(private val project: Project) : BoundConfigurable(DISPLAY_NAME) {

    private val state: FlowLensSettings.State
        get() = FlowLensSettings.getInstance(project).state

    override fun createPanel(): DialogPanel = panel {
        group(FlowLensBundle.message("settings.group.limits")) {
            row(FlowLensBundle.message("settings.max.depth")) {
                intTextField(FlowLensSettings.MIN_DEPTH..FlowLensSettings.MAX_DEPTH)
                    .columns(4)
                    .bindIntText({ state.maxDepth }, { state.maxDepth = it })
            }
            row {
                comment(FlowLensBundle.message("settings.max.depth.comment"))
            }
            row(FlowLensBundle.message("settings.max.nodes")) {
                intTextField(FlowLensSettings.MIN_NODES..FlowLensSettings.MAX_NODES)
                    .columns(4)
                    .bindIntText({ state.maxNodes }, { state.maxNodes = it })
            }
            row {
                comment(FlowLensBundle.message("settings.max.nodes.comment"))
            }
        }
        group(FlowLensBundle.message("settings.group.traversal")) {
            row {
                checkBox(FlowLensBundle.message("settings.include.tests"))
                    .bindSelected({ state.includeTests }, { state.includeTests = it })
            }
            row {
                comment(FlowLensBundle.message("settings.include.tests.comment"))
            }
            row {
                checkBox(FlowLensBundle.message("settings.include.libraries"))
                    .bindSelected({ state.includeLibraries }, { state.includeLibraries = it })
            }
            row {
                comment(FlowLensBundle.message("settings.include.libraries.comment"))
            }
        }
    }

    companion object {
        /** Also the settings-tree label; not user-visible prose, so not localized. */
        const val DISPLAY_NAME: String = "Flow Lens"
    }
}
