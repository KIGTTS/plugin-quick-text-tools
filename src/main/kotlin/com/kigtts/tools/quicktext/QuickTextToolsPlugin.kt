package com.kigtts.tools.quicktext

import android.text.TextUtils
import com.kigtts.plugin.api.*
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.Locale
import java.util.UUID

/** Optional SDK example, installed separately from the host. */
class QuickTextToolsPlugin : KigPlugin {
    override val descriptor = PluginDescriptor("tools.quick-text-example", "快捷文本工具示例", "创建计算器页面卡片并演示导入导出", version = "0.8.3")
    override val localization = PluginLocalization("zh-CN", listOf("zh-CN", "zh-CN-x-casual", "en", "ko", "ja"))
    private val widgetKey = "${descriptor.id}/widget"
    private lateinit var environment: PluginEnvironment
    private var strings = PluginStrings(emptyMap())

    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment
        strings = PluginStrings.fromAssets(environment.assets)
    }

    override val contributions = PluginContributions(drawerEntries = listOf(DrawerEntry("quick-tools", "快捷文本工具", "tools", PluginIcon.TEXT)))
    override fun contributions(context: PluginContext) = PluginContributions(drawerEntries = listOf(
        DrawerEntry("quick-tools", strings.text("title", context.locale), "tools", PluginIcon.TEXT)
    ))

    override fun page(pageId: String, context: PluginContext): PluginPage? {
        if (pageId != "tools") return null
        fun text(key: String) = strings.text(key, context.locale)
        return PluginPage("tools", text("title"), listOf(
            PageBlock.Text(text("description")),
            PageBlock.Actions(listOf(
                PageAction("create-calculator", text("calculator.create"), PluginIcon.ADD),
                PageAction("export", text("export"), PluginIcon.EXPORT),
                PageAction("import", text("import"), PluginIcon.MENU)
            ))
        ))
    }

    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome {
        fun text(key: String) = strings.text(key, context.locale)
        if (actionId == "calculator/result") return PluginOutcome.ReplaceEditorText(context.inputs["result"].orEmpty().take(256))
        if (actionId == "import") return PluginOutcome.ImportResource(text("import"), "imports", "import-complete")
        val broker = environment.quickTextBroker ?: return PluginOutcome.Message(text("error.host_unavailable"))
        return try {
            when (actionId) {
                "create-calculator" -> createCalculator(broker, context.locale, ::text)
                "export" -> when (val reply = broker.request(QuickTextCommand.Export())) {
                    is QuickTextReply.Resource -> PluginOutcome.ExportResource(text("export"), reply.source, "${text("export.file")}.kigcards")
                    else -> message(reply, ::text)
                }
                "import-complete" -> {
                    val path = context.inputs["resourcePath"] ?: return PluginOutcome.Message(text("import.missing"))
                    val snapshot = query(broker) ?: return PluginOutcome.Message(text("error.read"))
                    apply(broker, broker.request(QuickTextCommand.Import(PluginResource(path), UUID.randomUUID().toString(), snapshot.revision)), ::text)
                }
                else -> PluginOutcome.Message(text("error.unknown"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PluginOutcome.Message(text("error.operation"))
        }
    }

    private suspend fun createCalculator(broker: QuickTextBroker, locale: String, text: (String) -> String): PluginOutcome {
        val snapshot = query(broker) ?: return PluginOutcome.Message(text("error.read"))
        val existingGroups = snapshot.entries.filter { it.metadata[widgetKey] == "calculator" }.map { it.groupId }.toSet()
        val groupNames = localization.supportedLocales.map { strings.text("calculator.group", it) }
        val group = snapshot.groups.firstOrNull { it.id in existingGroups }
            ?: snapshot.groups.firstOrNull { it.name in groupNames }
            ?: QuickGroup(name = text("calculator.group"), icon = "INFO")
        val path = "resources/widgets/${UUID.randomUUID()}"
        val title = text("calculator.title")
        val html = File(environment.assets, "calculator.html").readText(Charsets.UTF_8)
            .replace("{{language}}", Locale.forLanguageTag(locale).toLanguageTag())
            .replace("{{title}}", TextUtils.htmlEncode(title))
            .replace("{{send}}", TextUtils.htmlEncode(text("calculator.send")))
            .replace("{{error}}", TextUtils.htmlEncode(text("calculator.error")))
        val file = environment.storage.file("$path/index.html")
        file.parentFile?.mkdirs()
        file.writeText(html, Charsets.UTF_8)

        val reply = broker.request(QuickTextCommand.PublishPage(PluginResource(path), name = title, allowedActions = listOf("calculator/result")))
        if (reply !is QuickTextReply.Media) return message(reply, text)
        val edits = buildList {
            if (group !in snapshot.groups) add(QuickTextEdit.PutGroup(group))
            add(QuickTextEdit.PutEntry(QuickEntry(groupId = group.id, text = title, title = title, kind = QuickCardKind.PAGE,
                mediaId = reply.value.id, metadata = mapOf(widgetKey to "calculator"))))
        }
        return apply(broker, broker.request(QuickTextCommand.Prepare(UUID.randomUUID().toString(), snapshot.revision, edits)), text)
    }

    private suspend fun query(broker: QuickTextBroker) =
        (broker.request(QuickTextCommand.Query(limit = 0)) as? QuickTextReply.Snapshot)?.value

    private suspend fun apply(broker: QuickTextBroker, reply: QuickTextReply, text: (String) -> String): PluginOutcome {
        if (reply !is QuickTextReply.Prepared) return message(reply, text)
        val result = broker.request(QuickTextCommand.Commit(reply.value.token))
        if (result is QuickTextReply.Applied) {
            broker.request(QuickTextCommand.OpenUi(QuickUiTarget.MANAGE))
            return PluginOutcome.Message(text("updated"))
        }
        if (result is QuickTextReply.Failure && result.code == QuickTextError.CONFIRMATION_REQUIRED) {
            broker.request(QuickTextCommand.OpenUi(QuickUiTarget.REVIEW, reply.value.token))
        }
        return message(result, text)
    }

    private fun message(reply: QuickTextReply, text: (String) -> String) = PluginOutcome.Message(
        text(when ((reply as? QuickTextReply.Failure)?.code) {
            QuickTextError.PERMISSION_DENIED -> "error.permission_denied"
            QuickTextError.NOT_FOUND -> "error.not_found"
            QuickTextError.INVALID -> "error.invalid"
            QuickTextError.CONFLICT -> "error.conflict"
            QuickTextError.BUSY -> "error.busy"
            QuickTextError.CONFIRMATION_REQUIRED -> "error.confirm"
            QuickTextError.HOST_UNAVAILABLE -> "error.host_unavailable"
            QuickTextError.TOO_LARGE -> "error.too_large"
            null -> "error.operation"
        })
    )
}
