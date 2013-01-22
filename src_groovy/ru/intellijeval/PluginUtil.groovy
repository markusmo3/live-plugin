/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.intellijeval
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerAdapter
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.unscramble.UnscrambleDialog
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable

import javax.swing.*

import static com.intellij.execution.ui.ConsoleViewContentType.NORMAL_OUTPUT
/**
 * User: dima
 * Date: 11/08/2012
 */
@SuppressWarnings("GroovyUnusedDeclaration")
class PluginUtil {
	// TODO add javadocs

	private static final WeakHashMap<ProjectManagerListener, String> pmListenerToId = new WeakHashMap()

	// TODO use actual intellij logger
	static log(@Nullable htmlBody, @Nullable title = "",
	           NotificationType notificationType = NotificationType.INFORMATION, String groupDisplayId = "") {
		def notification = new Notification(groupDisplayId, String.valueOf(title), String.valueOf(htmlBody), notificationType)
		ApplicationManager.application.messageBus.syncPublisher(Notifications.TOPIC).notify(notification)
	}

	/**
	 * Shows popup balloon notification.
	 * (It actually sends IDE notification event which by default shows "balloon".
	 * This also means that message will be added to "Event Log" console.)
	 *
	 * See "IDE Settings - Notifications".
	 *
	 * @param message message to display (can have html tags in it)
	 * @param title (optional) popup title
	 * @param notificationType (optional) see https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/notification/NotificationType.java
	 * @param groupDisplayId (optional) an id to group notifications by (can be configured in "IDE Settings - Notifications")
	 */
	static show(@Nullable message, @Nullable title = "",
	            NotificationType notificationType = NotificationType.INFORMATION, String groupDisplayId = "") {
		SwingUtilities.invokeLater({
			def notification = new Notification(groupDisplayId, String.valueOf(title), String.valueOf(message), notificationType)
			ApplicationManager.application.messageBus.syncPublisher(Notifications.TOPIC).notify(notification)
		} as Runnable)
	}

	/**
	 * @param e exception to show
	 * @param consoleTitle (optional; might be useful to have different titles if there are several open consoles)
	 * @param project console will be displayed in the window of this project
	 */
	static showExceptionInConsole(Exception e, consoleTitle = "", @NotNull Project project) {
		def writer = new StringWriter()
		e.printStackTrace(new PrintWriter(writer))
		String text = UnscrambleDialog.normalizeText(writer.buffer.toString())

		showInConsole(text, String.valueOf(consoleTitle), project, ConsoleViewContentType.ERROR_OUTPUT)
	}

	/**
	 * @param text text to show
	 * @param consoleTitle (optional)
	 * @param project console will be displayed in the window of this project
	 * @param contentType (optional) see https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/execution/ui/ConsoleViewContentType.java
	 */
	// TODO show reuse the same console and append to output
	static showInConsole(String text, String consoleTitle = "", @NotNull Project project, ConsoleViewContentType contentType = NORMAL_OUTPUT) {
		Util.displayInConsole(consoleTitle, text, contentType, project)
	}

	/**
	 * Registers action in IDE.
	 * If there is already an action with {@code actionId}, it will be replaced.
	 * (The main reason to replace action is to be able to incrementally add code to callback without restarting IDE.)
	 *
	 * @param actionId unique identifier for action
	 * @param keyStroke (optional) e.g. "ctrl alt shift H" or "alt C, alt H" for double key stroke.
	 *        Note that letters must be uppercase, modification keys lowercase.
	 * @param actionGroupId TODO
	 * @param actionText TODO
	 * @param callback code to run when action is invoked
	 * @return instance of created action
	 */
	static AnAction registerAction(String actionId, String keyStroke = "",
	                               String actionGroupId = null, String actionText = "", Closure callback) {
		def action = new AnAction(actionText) {
			@Override void actionPerformed(AnActionEvent event) { callback.call(event) }
		}

		def actionManager = ActionManager.instance
		def actionGroup = findActionGroup(actionGroupId)

		def alreadyRegistered = (actionManager.getAction(actionId) != null)
		if (alreadyRegistered) {
			actionGroup?.remove(actionManager.getAction(actionId))
			actionManager.unregisterAction(actionId)
		}

		registerKeyStroke(actionId, keyStroke)
		actionManager.registerAction(actionId, action)
		actionGroup?.add(action)

		log("Action '${actionId}' registered")

		action
	}

	/**
	 * Registers a tool window in IDE.
	 * If there is already a tool window with {@code toolWindowId}, it will be replaced.
	 *
	 * @param toolWindowId unique identifier for tool window
	 * @param component content of the tool window
	 * @param location (optional) see https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/openapi/wm/ToolWindowAnchor.java
	 */
	static registerToolWindow(String toolWindowId, JComponent component, ToolWindowAnchor location = ToolWindowAnchor.RIGHT) {
		def listener = new ProjectManagerAdapter() {
			@Override void projectOpened(Project project) {
				registerToolWindowIn(project, toolWindowId, component)
			}

			@Override void projectClosed(Project project) {
				unregisterToolWindowIn(project, toolWindowId)
			}
		}
		pmListenerToId[listener] = toolWindowId
		ProjectManager.instance.addProjectManagerListener(listener)

		ProjectManager.instance.openProjects.each { project ->
			registerToolWindowIn(project, toolWindowId, component)
		}

		log("Toolwindow '${toolWindowId}' registered")
	}

	/**
	 * This method exists for reference only.
	 * For more dialogs see https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/openapi/ui/Messages.java
	 */
	@Nullable static String showInputDialog(String message, String title, @Nullable Icon icon) {
		Messages.showInputDialog(message, title, icon)
	}


	private static DefaultActionGroup findActionGroup(String actionGroupId) {
		if (actionGroupId != null && actionGroupId) {
			def action = ActionManager.instance.getAction(actionGroupId)
			action instanceof DefaultActionGroup ? action : null
		} else {
			null
		}
	}

	private static void registerKeyStroke(String actionId, String keyStroke) {
		def keymap = KeymapManager.instance.activeKeymap
		keymap.removeAllActionShortcuts(actionId)
		if (!keyStroke.empty) {
			if (keyStroke.contains(",")) {
				def firstKeyStroke = { keyStroke[0..<keyStroke.indexOf(",")].trim() }
				def secondKeyStroke = { keyStroke[(keyStroke.indexOf(",") + 1)..-1].trim() }
				keymap.addShortcut(actionId,
						new KeyboardShortcut(
								KeyStroke.getKeyStroke(firstKeyStroke()),
								KeyStroke.getKeyStroke(secondKeyStroke())))
			} else {
				keymap.addShortcut(actionId,
						new KeyboardShortcut(
								KeyStroke.getKeyStroke(keyStroke), null))
			}
		}
	}

	private static ToolWindow registerToolWindowIn(Project project, String id, JComponent component) {
		def manager = ToolWindowManager.getInstance(project)

		if (manager.getToolWindow(id) != null) {
			manager.unregisterToolWindow(id)
		}

		def toolWindow = manager.registerToolWindow(id, false, ToolWindowAnchor.RIGHT)
		def content = ContentFactory.SERVICE.instance.createContent(component, "", false)
		toolWindow.contentManager.addContent(content)
		toolWindow
	}

	private static unregisterToolWindowIn(Project project, String id) {
		ToolWindowManager.getInstance(project).unregisterToolWindow(id)
	}

	static catchingAll(Closure closure) {
		try {

			closure.call()

		} catch (Exception e) {
			ProjectManager.instance.openProjects.each { Project project ->
				showExceptionInConsole(e, e.class.simpleName, project)
			}
		}
	}

	// TODO method to edit content of a file (read-write action wrapper)
	// TODO method to get current virtual file
	// TODO method to iterate over all virtual files in project
	// TODO method to iterate over PSI files in project


	static accessField(Object o, String fieldName, Closure callback) {
		catchingAll {
			for (field in o.class.declaredFields) {
				if (field.name == fieldName) {
					field.setAccessible(true)
					callback(field.get(o))
					return
				}
			}
		}
	}

	static registerInMetaClasses(AnActionEvent anActionEvent) { // TODO
		[Object.metaClass, Class.metaClass].each {
			it.actionEvent = { anActionEvent }
			it.project = { actionEvent().getData(PlatformDataKeys.PROJECT) }
			it.editor = { actionEvent().getData(PlatformDataKeys.EDITOR) }
			it.fileText = { actionEvent().getData(PlatformDataKeys.FILE_TEXT) }
		}
	}
}
