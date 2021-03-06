import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import liveplugin.PluginUtil
import liveplugin.invokeLaterOnEDT
import liveplugin.show
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedFunction


val kotlinIsSupportedByIde = Language.findLanguageByID("kotlin") != null
if (kotlinIsSupportedByIde) {
    PluginUtil.registerIntention(pluginDisposable, RenameKotlinFunctionToUseSpacesIntention())
    PluginUtil.registerIntention(pluginDisposable, RenameKotlinFunctionToUseCamelCaseIntention())
    if (!isIdeStartup) show("Reloaded KotlinFunctionNameWithSpacesIntention")
}

class RenameKotlinFunctionToUseSpacesIntention: PsiElementBaseIntentionAction(), Util {
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement) =
        element.isInKotlinFile() && element.findKtNamedFunction()?.name?.contains(' ') == false

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        invokeLaterOnEDT {
            doRenameRefactoring(element, project, editor, this::renameToSpaces)
        }
    }

    private fun renameToSpaces(name: String): String {
        val chars = name.flatMap {
            if (Character.isUpperCase(it)) listOf(' ', it.toLowerCase())
            else listOf(it)
        }
        return "`" + String(chars.toCharArray()) + "`"
    }

    override fun getText() = "Rename to use spaces"
    override fun getFamilyName() = "RenameKotlinFunctionIntention"
}

class RenameKotlinFunctionToUseCamelCaseIntention: PsiElementBaseIntentionAction(), Util {
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement) =
        element.isInKotlinFile() && element.findKtNamedFunction()?.name?.contains(' ') == true

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        invokeLaterOnEDT {
            doRenameRefactoring(element, project, editor, this::renameToCamelCase)
        }
    }

    private fun renameToCamelCase(name: String): String {
        return name.split(' ')
            .filter { it.isNotEmpty() }
            .mapIndexed { i, s ->
                if (i == 0) s
                else s[0].toUpperCase() + s.drop(1)
            }
            .joinToString("")
    }

    override fun getText() = "Rename to use camel case"
    override fun getFamilyName() = "RenameKotlinFunctionIntention"
}

interface Util {
    fun doRenameRefactoring(element: PsiElement, project: Project, editor: Editor?, rename: (String) -> String) {
        val function = element.findKtNamedFunction()!!
        val newName = rename(function.name!!)

        val processor = RenamePsiElementProcessor.forElement(element)
        processor.createRenameDialog(project, function as PsiElement, function as PsiElement, editor).let {
            try {
                it.setPreviewResults(false)
                it.performRename(newName)
            } finally {
                it.close(DialogWrapper.CANCEL_EXIT_CODE) // to avoid dialog leak
            }
        }
    }

    fun PsiElement.findKtNamedFunction(): KtNamedFunction? {
        val isOnFunctionIdentifier =
            this is LeafPsiElement &&
            elementType == KtTokens.IDENTIFIER &&
            parent.let {
                it != null && KtNamedFunction::class.java.isAssignableFrom(it.javaClass)
            }
        return if (isOnFunctionIdentifier) parent as KtNamedFunction else null
    }

    fun PsiElement.isInKotlinFile(): Boolean {
        val fileType = (containingFile?.fileType as? LanguageFileType) ?: return false
        return fileType.language.id.toLowerCase() == "kotlin"
    }
}
