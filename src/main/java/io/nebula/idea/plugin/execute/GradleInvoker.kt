package io.nebula.idea.plugin.execute

import com.android.tools.idea.gradle.project.build.invoker.BuildStopper
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker
import com.android.tools.idea.gradle.project.build.invoker.GradleTasksExecutorFactory
import com.android.tools.idea.gradle.project.build.output.*
import com.android.tools.idea.gradle.util.AndroidGradleSettings
import com.android.tools.idea.gradle.util.GradleUtil
import com.android.tools.idea.gradle.util.LocalProperties
import com.android.tools.idea.sdk.IdeSdks
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.Multimap
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.impl.*
import com.intellij.build.output.BuildOutputInstantReaderImpl
import com.intellij.build.output.JavacOutputParser
import com.intellij.build.output.KotlincOutputParser
import com.intellij.icons.AllIcons.Actions
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.externalSystem.model.task.*
import com.intellij.openapi.externalSystem.model.task.event.ExternalSystemTaskExecutionEvent
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import io.nebula.idea.plugin.filter.AndroidReRunBuildFilter
import java.io.File
import java.io.IOException
import java.util.*
import java.util.stream.Collectors
import java.util.stream.Stream

class GradleInvoker constructor(
    val project: Project,
    private val myDocumentManager: FileDocumentManager,
    private val myTaskExecutorFactory: GradleTasksExecutorFactory
) {
    private val myOneTimeGradleOptions: MutableList<String>
    private val myLastBuildTasks: Multimap<String, String>
    private val myBuildStopper: BuildStopper

    companion object {
        @Volatile
        private var INSTANCE: GradleInvoker? = null

        fun getInstance(project: Project): GradleInvoker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GradleInvoker(project, FileDocumentManager.getInstance()).also { INSTANCE = it }
            }
        }
    }

    constructor(project: Project, documentManager: FileDocumentManager) : this(
        project,
        documentManager,
        GradleTasksExecutorFactory()
    )

    init {
        this.myOneTimeGradleOptions = ArrayList()
        this.myLastBuildTasks = ArrayListMultimap.create()
        this.myBuildStopper = BuildStopper()
    }

    fun executeTasks(buildFilePath: File, gradleTasks: List<String>, listener: ExecuteListener) {
        this.executeTasks(buildFilePath, gradleTasks, this.myOneTimeGradleOptions, listener)
    }

    fun executeTasks(
        buildFilePath: File,
        gradleTasks: List<String>,
        commandLineArguments: List<String>,
        listener: ExecuteListener
    ) {
        val jvmArguments = arrayListOf<String>()
        if (ApplicationManager.getApplication().isUnitTestMode) {
            val localProperties: LocalProperties
            try {
                localProperties = LocalProperties(this.project)
            } catch (var8: IOException) {
                throw RuntimeException(var8)
            }

            if (localProperties.androidSdkPath == null) {
                val androidHomePath = IdeSdks.getInstance().androidSdkPath
                if (androidHomePath != null) {
                    jvmArguments.add(AndroidGradleSettings.createAndroidHomeJvmArg(androidHomePath.path))
                }
            }
        }

        val request = Request(this.project, buildFilePath, gradleTasks)
        val buildTaskListener = this.createBuildTaskListener(request, "Build", listener)
        request.setJvmArguments(jvmArguments).setCommandLineArguments(commandLineArguments)
            .setTaskListener(buildTaskListener)
        this.executeTasks(request)
    }

    private fun createBuildTaskListener(
        request: Request,
        executionName: String,
        listener: ExecuteListener
    ): ExternalSystemTaskNotificationListener {
        val buildViewManager = ServiceManager.getService(this.project, BuildViewManager::class.java) as BuildViewManager
        val buildOutputParsersWrappers = Stream.of(
            GradleBuildOutputParser(),
            ClangOutputParser(),
            CmakeOutputParser(),
            XmlErrorOutputParser(),
            AndroidGradlePluginOutputParser(),
            DataBindingOutputParser(),
            JavacOutputParser(),
            KotlincOutputParser()
        ).map {
            BuildOutputParserWrapper(it)
        }.collect(Collectors.toList()) as List<BuildOutputParserWrapper>
        val buildOutputInstantReader = BuildOutputInstantReaderImpl(
            request.taskId,
            buildViewManager,
            buildOutputParsersWrappers
        )

        try {
            return object : ExternalSystemTaskNotificationListenerAdapter() {
                private var myBuildFailed = false

                override fun onStart(id: ExternalSystemTaskId, workingDir: String) {
                    val restartAction = object : AnAction() {
                        override fun update(e: AnActionEvent) {
                            super.update(e)
                            e.presentation.isEnabled = !myBuildStopper.contains(id)
                        }

                        override fun actionPerformed(e: AnActionEvent) {
                            myBuildFailed = false
                            buildOutputParsersWrappers.forEach { it.reset() }
                            executeTasks(request)
                        }
                    }
                    this.myBuildFailed = false
                    buildOutputParsersWrappers.forEach { it.reset() }
                    val presentation = restartAction.templatePresentation
                    presentation.text = "Restart"
                    presentation.description = "Restart"
                    presentation.icon = Actions.Compile
                    val eventTime = System.currentTimeMillis()
                    val event = StartBuildEventImpl(
                        DefaultBuildDescriptor(id, executionName, workingDir, eventTime),
                        "running..."
                    )
                    event.withRestartAction(restartAction).withExecutionFilter(AndroidReRunBuildFilter(workingDir))
                    buildViewManager.onEvent(event)
                }

                override fun onStatusChange(event: ExternalSystemTaskNotificationEvent) {
                    if (event is ExternalSystemTaskExecutionEvent) {
                        val buildEvent = ExternalSystemUtil.convert(event)
                        buildViewManager.onEvent(buildEvent)
                    }

                }

                override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {
                    buildViewManager.onEvent(OutputBuildEventImpl(id, text, stdOut))
                }

                override fun onEnd(id: ExternalSystemTaskId) {
                    if (this.myBuildFailed) {
                        sendBuildFailureMetrics(buildOutputParsersWrappers, project)
                    }
                    if (id == request.taskId) {
                        listener.onEnd()
                    }
                }

                override fun onSuccess(id: ExternalSystemTaskId) {
                    val event = FinishBuildEventImpl(
                        id,
                        null as Any?,
                        System.currentTimeMillis(),
                        "completed successfully",
                        SuccessResultImpl()
                    )
                    buildViewManager.onEvent(event)
                    if (id == request.taskId) {
                        listener.onSucceed()
                    }
                }

                override fun onFailure(id: ExternalSystemTaskId, e: Exception) {
                    this.myBuildFailed = true
                    val title = "$executionName failed"
                    val failureResult = ExternalSystemUtil.createFailureResult(
                        title,
                        e,
                        GradleUtil.GRADLE_SYSTEM_ID,
                        project
                    )
                    buildViewManager.onEvent(
                        FinishBuildEventImpl(
                            id,
                            null as Any?,
                            System.currentTimeMillis(),
                            "build failed",
                            failureResult
                        )
                    )
                    if (id == request.taskId) {
                        listener.onFailed()
                    }
                }

                override fun onCancel(id: ExternalSystemTaskId) {
                    super.onCancel(id)
                    val event = FinishBuildEventImpl(
                        id,
                        null as Any?,
                        System.currentTimeMillis(),
                        "cancelled",
                        SkippedResultImpl()
                    )
                    buildViewManager.onEvent(event)
                }
            }
        } catch (var7: Exception) {
            buildOutputInstantReader.close()
            throw var7
        }

    }

    fun executeTasks(request: Request) {
        val buildFilePath = request.buildFilePath.path
        this.myLastBuildTasks.removeAll(buildFilePath)
        val gradleTasks = request.gradleTasks
        this.myLastBuildTasks.putAll(buildFilePath, gradleTasks)
        if (gradleTasks.isNotEmpty()) {
            val executor = this.myTaskExecutorFactory.create(request.toRealRequest(), this.myBuildStopper)
            val executeTasksTask = Runnable {
                this.myDocumentManager.saveAllDocuments()
                executor.queue()
            }
            when {
                ApplicationManager.getApplication().isDispatchThread -> executeTasksTask.run()
                request.isWaitForCompletion -> executor.queueAndWaitForCompletion()
                else -> TransactionGuard.getInstance().submitTransactionAndWait(executeTasksTask)
            }

        }
    }

    class Request @JvmOverloads constructor(
        private val project: Project,
        internal val buildFilePath: File,
        gradleTasks: List<String>,
        internal val taskId: ExternalSystemTaskId = ExternalSystemTaskId.create(
            GradleUtil.GRADLE_SYSTEM_ID,
            ExternalSystemTaskType.EXECUTE_TASK,
            project
        )
    ) {
        internal val gradleTasks: List<String>
        private val myJvmArguments: MutableList<String>
        private val myCommandLineArguments: MutableList<String>
        private val myEnv: MutableMap<String, String>
        var isPassParentEnvs: Boolean = false
            private set
        private var myTaskListener: ExternalSystemTaskNotificationListener? = null
        internal var isWaitForCompletion: Boolean = false
            private set

        internal val jvmArguments: List<String>
            get() = this.myJvmArguments

        internal val commandLineArguments: List<String>
            get() = this.myCommandLineArguments

        init {
            this.isPassParentEnvs = true
            this.gradleTasks = ArrayList(gradleTasks)
            this.myJvmArguments = ArrayList()
            this.myCommandLineArguments = ArrayList()
            this.myEnv = LinkedHashMap()
        }

        fun setJvmArguments(jvmArguments: List<String>): Request {
            this.myJvmArguments.clear()
            this.myJvmArguments.addAll(jvmArguments)
            return this
        }

        fun setCommandLineArguments(commandLineArguments: List<String>): Request {
            this.myCommandLineArguments.clear()
            this.myCommandLineArguments.addAll(commandLineArguments)
            return this
        }

        fun setTaskListener(taskListener: ExternalSystemTaskNotificationListener?): Request {
            this.myTaskListener = taskListener
            return this
        }

        fun toRealRequest(): GradleBuildInvoker.Request {
            val request = GradleBuildInvoker.Request(project, buildFilePath, gradleTasks, taskId)
            request.taskListener = myTaskListener
            return request
        }
    }

    interface ExecuteListener {
        fun onSucceed()

        fun onFailed()

        fun onEnd()
    }


}
