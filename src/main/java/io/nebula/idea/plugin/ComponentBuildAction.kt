package io.nebula.idea.plugin

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.model.GradleFileModelImpl
import com.android.tools.idea.gradle.dsl.parser.elements.*
import com.android.tools.idea.gradle.dsl.parser.files.GradleDslFile
import com.android.tools.idea.gradle.project.model.AndroidModuleModel
import com.android.tools.idea.run.deployment.AndroidExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.impl.local.LocalFileSystemBase
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.nebula.idea.plugin.detector.ModifyChecker
import io.nebula.idea.plugin.dslmodule.ComponentBlockElement
import io.nebula.idea.plugin.dslmodule.DependenciesBlockElement
import io.nebula.idea.plugin.utils.Notifier
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrApplicationStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrCommandArgumentList
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression

class ComponentBuildAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        project ?: return
//        LocalFileSystemBase.getInstance().refresh(false)
        Notifier.notifyBalloon(project, "Component build", "start component build process.")

        val target = ExecutionTargetManager.getInstance(e.project!!).activeTarget
        if (target is AndroidExecutionTarget) {
            println(target.iDevice?.serialNumber + ":::")
        }
        val configuration = RunManager.getInstance(project).selectedConfiguration ?: return
        val moduleName = configuration.name
        val moduleDepSet = hashSetOf(moduleName)
        println("configuration name: $moduleName")
        resolveConfigurationDependencies(moduleName, project, moduleDepSet)
        moduleDepSet.forEach {
            println("name:" + it)
        }
        moduleDepSet.remove(moduleName)
        ModifyChecker.checkForModifiedModuleList(project, moduleDepSet).forEach {
            println("changed module:$it")
        }
//        ProgramRunnerUtil.executeConfiguration(
//            RunManager.getInstance(project).selectedConfiguration!!,
//            DefaultRunExecutor.getRunExecutorInstance()
//        )
    }

    private fun resolveConfigurationDependencies(
        moduleName: String,
        project: Project,
        moduleDepSet: HashSet<String>
    ) {
        val module = ModuleManager.getInstance(project).modules.find { it.name == moduleName } ?: return
        val gradleBuildModule = ProjectBuildModel.get(project).getModuleBuildModel(module)

        if (gradleBuildModule !is GradleFileModelImpl) {
            return
        }
        val application = ApplicationManager.getApplication()
        val groovyFile =
            application.runReadAction(Computable<PsiFile> {
                PsiManager.getInstance(project).findFile(
                    gradleBuildModule.dslFile.file
                )!!
            })
        if (groovyFile is GroovyFile) {
            groovyFile.acceptChildren(object : GroovyElementVisitor() {
                override fun visitMethodCallExpression(e: GrMethodCallExpression) {
                    parseMethodCall(e, gradleBuildModule.dslFile)
                }
            })
        }
        val componentBlockElement =
            gradleBuildModule.dslFile.getPropertyElement("component", ComponentBlockElement::class.java) ?: return

        val dependenciesBlockElement =
            componentBlockElement.getPropertyElement("dependencies", DependenciesBlockElement::class.java) ?: return
        val implLiteral = dependenciesBlockElement.getPropertyElement("implementation", GradleDslLiteral::class.java)
        val interLiteral = dependenciesBlockElement.getPropertyElement("interfaceApi", GradleDslLiteral::class.java)

        if (implLiteral != null) {
            val depModule = implLiteral.value.toString().split(':')[1]
            if (moduleDepSet.add(depModule)) {
                resolveConfigurationDependencies(depModule, project, moduleDepSet)
            }
        }

        if (interLiteral != null) {
            val depModule = interLiteral.value.toString().split(':')[1]
            if (moduleDepSet.add(depModule)) {
                resolveConfigurationDependencies(depModule, project, moduleDepSet)
            }
        }
    }

    private fun parseMethodCall(expression: GrMethodCallExpression, dslElement: GradlePropertiesDslElement) {
        val referenceExpression =
            PsiTreeUtil.findChildOfType(expression, GrReferenceExpression::class.java) as GrReferenceExpression
        val name = GradleNameElement.from(referenceExpression)
        if (name.name() != "component" && name.name() != "dependencies") {
            return
        }
        val closureArguments = expression.closureArguments
        val argumentList = expression.argumentList
        if (argumentList.allArguments.isNotEmpty() || closureArguments.isEmpty()) {
            // This element is a method call with arguments and an optional closure associated with it.
            // ex: compile("dependency") {}
            // ignore this for plugin
            return
        }
        // Now this element is pure block element, i.e a method call with no argument but just a closure argument. So, here just process the
        // closure and treat it as a block element.
        // ex: android {}
        val blockElement = getBlockElement(listOf(name.name()), dslElement) ?: return
        val closableBlock = closureArguments[0]
        parseClosure(closableBlock, blockElement)
    }

    private fun parseClosure(closure: GrClosableBlock, blockElement: GradlePropertiesDslElement) {
        closure.acceptChildren(object : GroovyElementVisitor() {
            override fun visitMethodCallExpression(methodCallExpression: GrMethodCallExpression) {
                parseMethodCall(methodCallExpression, blockElement)
            }

            override fun visitApplicationStatement(e: GrApplicationStatement) {
                parseMethodCallExpression(e, blockElement)
            }
        })
    }

    private fun parseMethodCallExpression(
        e: GrApplicationStatement,
        blockElement: GradlePropertiesDslElement
    ) {
        val referenceExpression = PsiTreeUtil.getChildOfType(e, GrReferenceExpression::class.java) ?: return
        val name = GradleNameElement.from(referenceExpression)
        if (name.isEmpty || (name.name() != "interfaceApi" && name.name() != "implementation")) {
            return
        }
        val argumentList = PsiTreeUtil.getNextSiblingOfType(referenceExpression, GrCommandArgumentList::class.java)
        val arguments = argumentList?.allArguments ?: return
        if (arguments[0] !is GrExpression) {
            return
        }

        val propertyElement = createExpressionElement(
            blockElement,
            argumentList,
            name,
            arguments[0] as GrExpression
        ) as GradleDslLiteral
        blockElement.addParsedElement(propertyElement)
    }

    private fun getBlockElement(
        qualifyingParts: List<String>,
        dslElement: GradlePropertiesDslElement
    ): GradlePropertiesDslElement? {
        var result: GradlePropertiesDslElement? = null
        qualifyingParts.forEach {
            if (dslElement is GradleDslFile) {
                if (it == "component") {
                    result = ComponentBlockElement(dslElement)
                }
            }
            if (dslElement is ComponentBlockElement) {
                if (it == "dependencies") {
                    result = DependenciesBlockElement(dslElement)
                }
            }
        }
        if (result != null) {
            dslElement.addParsedElement(result!!)
        }
        return result
    }

    private fun createExpressionElement(
        parent: GradleDslElement,
        psiElement: GroovyPsiElement,
        name: GradleNameElement,
        expression: GrExpression
    ): GradleDslExpression {
        //only for normal
        return getExpressionElement(parent, psiElement, name, expression)
    }

    private fun getExpressionElement(
        parentElement: GradleDslElement,
        psiElement: GroovyPsiElement,
        propertyName: GradleNameElement,
        propertyExpression: GrExpression
    ): GradleDslExpression {
        if (propertyExpression is GrLiteral) { // ex: compileSdkVersion 23 or compileSdkVersion = "android-23"
            return GradleDslLiteral(parentElement, psiElement, propertyName, propertyExpression, false)
        }
        throw RuntimeException("got expression actually type is ${propertyExpression.javaClass.name} but except is GrLiteral")
    }

    private fun combineFlavors(module: Module): String {
        val model = AndroidModuleModel.get(module)
        val flavors = model?.selectedVariant?.productFlavors
        var str = ""
        flavors?.forEach {
            str += upperFirstChat(it)
        }
        return str
    }

    private fun upperFirstChat(string: String): String {
        return string[0].toUpperCase() + string.substring(1)
    }
}

