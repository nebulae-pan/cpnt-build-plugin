package io.nebula.idea.plugin.dslmodule

import com.android.tools.idea.gradle.dsl.model.GradleDslBlockModel

class ComponentModel(private val element: ComponentBlockElement) : GradleDslBlockModel(element) {
    fun dependencies(): DependenciesModule? {
        val dependenciesElement = element.getPropertyElement("dependencies", DependenciesBlockElement::class.java)
        dependenciesElement ?: return null
        return DependenciesModule(dependenciesElement)
    }
}