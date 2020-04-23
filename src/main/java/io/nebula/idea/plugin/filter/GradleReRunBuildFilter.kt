package io.nebula.idea.plugin.filter

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import java.util.ArrayList

abstract class GradleReRunBuildFilter(
    // For a failed build with no options, you may see:
    //   "Run with --stacktrace option to get the stack trace. Run with --info or --debug option to get more log output."
    // With --debug turned on, you may see:
    //   "<timestamp> [ERROR] [org.gradle.BuildExceptionReporter] Run with --stacktrace option to get the stack trace."
    // With --info turned on, you may see:
    //   "Run with --stacktrace option to get the stack trace. Run with --debug option to get more log output."
    // With --stacktrace turned on, you may see:
    //   "Run with --info or --debug option to get more log output."

    protected val myBuildWorkingDir: String
) : Filter {
    private var line: String? = null
    private var links: MutableList<Filter.ResultItem>? = null
    private var lineStart: Int = 0

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (line == null) {
            return null
        }
        this.line = line
        this.lineStart = entireLength - line.length
        this.links = ArrayList()
        val trimLine = line.trim { it <= ' ' }
        if (!(trimLine.contains("Run with --") && (trimLine.endsWith("option to get the stack trace.")
                    || trimLine.endsWith("option to get more log output.")
                    || trimLine.endsWith("to get full insights.")))
        ) {
            return null
        }
        addLinkIfMatch("Run with --stacktrace", "--stacktrace")
        addLinkIfMatch("Run with --info", "--info")
        addLinkIfMatch("Run with --debug option", "--debug")
        addLinkIfMatch("--debug option", "--debug")
        addLinkIfMatch("Run with --scan", "--scan")
        return if (links!!.isEmpty()) {
            null
        } else Filter.Result(links!!)
    }

    protected abstract fun getHyperLinkInfo(options: List<String>): HyperlinkInfo

    private fun addLinkIfMatch(text: String, option: String) {
        val index = line!!.indexOf(text)
        if (index != -1) {
            links!!.add(createLink(lineStart + index, lineStart + index + text.length, option))
        }
    }

    private fun createLink(start: Int, end: Int, option: String): Filter.ResultItem {
        val options = ArrayList<String>()
        options.add(option)
        return Filter.ResultItem(start, end, getHyperLinkInfo(options))
    }
}