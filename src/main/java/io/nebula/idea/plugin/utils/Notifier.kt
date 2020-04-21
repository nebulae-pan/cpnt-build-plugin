package io.nebula.idea.plugin.utils

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notifier {
    val balloonNotificationGroup =
        NotificationGroup("component build info balloon", NotificationDisplayType.BALLOON, true)
    val noneNotificationGroup = NotificationGroup("component build info none", NotificationDisplayType.BALLOON, true)

    fun notifyBalloon(project: Project, content: String) {
        Notifier.notifyBalloon(project, "", content)
    }

    fun notifyBalloon(project: Project, title: String, content: String) {
        balloonNotificationGroup.createNotification(title, content, NotificationType.INFORMATION, null)
            .notify(project)
    }

    fun notify(project: Project, content: String) {
        notify(project, "", content)
    }

    fun notify(project: Project, title: String, content: String) {
        noneNotificationGroup.createNotification(title, content, NotificationType.INFORMATION, null)
            .notify(project)
    }
}