package io.nebula.idea.plugin.dslmodule

import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslBlockElement
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslElement
import com.android.tools.idea.gradle.dsl.parser.elements.GradleNameElement

class DependenciesBlockElement(element: GradleDslElement) :
    GradleDslBlockElement(element, GradleNameElement.create("dependencies")) {

}