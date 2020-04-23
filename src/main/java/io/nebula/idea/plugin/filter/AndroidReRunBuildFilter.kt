package io.nebula.idea.plugin.filter

import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker
import com.intellij.execution.filters.HyperlinkInfo
import java.io.File

class AndroidReRunBuildFilter(buildWorkingDir: String) : GradleReRunBuildFilter(buildWorkingDir) {
    override fun getHyperLinkInfo(options: List<String>): HyperlinkInfo {
        return HyperlinkInfo { project ->
            GradleBuildInvoker.getInstance(project!!).rebuildWithTempOptions(File(myBuildWorkingDir), options)
        }
    }

}