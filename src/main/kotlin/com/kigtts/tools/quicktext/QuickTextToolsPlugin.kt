package com.kigtts.tools.quicktext

import com.kigtts.plugin.api.*
import java.io.File
import java.util.UUID

/** Optional SDK example, never linked into the host or installed automatically. */
class QuickTextToolsPlugin:KigPlugin {
    override val descriptor=PluginDescriptor("tools.quick-text-example","快捷文本工具示例","创建计算器页面卡片并演示导入导出",version="0.8.1")
    private lateinit var environment:PluginEnvironment
    private val broker get()=requireNotNull(environment.quickTextBroker) {"宿主不支持快捷文本接口"}
    override suspend fun attach(environment:PluginEnvironment) {this.environment=environment}
    override val contributions=PluginContributions(drawerEntries=listOf(DrawerEntry("quick-tools","快捷文本工具","tools",PluginIcon.TEXT)))
    override fun page(pageId:String,context:PluginContext)=if(pageId=="tools")PluginPage("tools","快捷文本工具",listOf(
        PageBlock.Text("创建计算器快捷卡片，或导入、导出常用快捷文本。"),
        PageBlock.Actions(listOf(PageAction("create-calculator","创建计算器",PluginIcon.ADD),PageAction("export","导出快捷文本",PluginIcon.EXPORT),
            PageAction("import","导入快捷文本",PluginIcon.MENU)))
    )) else null
    override suspend fun execute(actionId:String,context:PluginContext):PluginOutcome {
        return when(actionId) {
            "create-calculator"->{
                val snapshot=query();val group=snapshot.groups.firstOrNull {it.name=="小工具"} ?: QuickGroup(name="小工具",icon="INFO")
                val path="resources/widgets/"+UUID.randomUUID()
                val file=environment.storage.file("$path/index.html");file.parentFile!!.mkdirs()
                File(environment.assets,"calculator.html").inputStream().use {input->file.outputStream().use {input.copyTo(it)}}
                val media=(broker.request(QuickTextCommand.PublishPage(PluginResource(path),name="计算器",allowedActions=listOf("calculator/result"))) as? QuickTextReply.Media)
                    ?: return PluginOutcome.Message("页面创建失败")
                val edits=mutableListOf<QuickTextEdit>();if(group !in snapshot.groups)edits+=QuickTextEdit.PutGroup(group)
                edits+=QuickTextEdit.PutEntry(QuickEntry(groupId=group.id,text="计算器",title="计算器",kind=QuickCardKind.PAGE,mediaId=media.value.id,
                    metadata=mapOf("${descriptor.id}/widget" to "calculator")))
                commit(QuickTextCommand.Prepare(UUID.randomUUID().toString(),snapshot.revision,edits))
            }
            "calculator/result"->PluginOutcome.ReplaceEditorText(context.inputs["result"].orEmpty().take(256))
            "export"->{val result=broker.request(QuickTextCommand.Export())
                if(result is QuickTextReply.Resource)PluginOutcome.ExportResource("导出快捷文本",result.source,"快捷文本.kigcards") else message(result)}
            "import"->PluginOutcome.ImportResource("导入快捷文本","imports","import-complete")
            "import-complete"->{val resource=context.inputs["resourcePath"] ?: return PluginOutcome.Message("未选择导入包")
                val result=broker.request(QuickTextCommand.Import(PluginResource(resource),UUID.randomUUID().toString(),query().revision))
                if(result is QuickTextReply.Prepared)apply(result) else message(result)}
            else->PluginOutcome.Message("未知操作")
        }
    }
    private suspend fun query()=(broker.request(QuickTextCommand.Query(limit=0)) as? QuickTextReply.Snapshot)?.value ?: error("无法读取快捷文本")
    private suspend fun commit(command:QuickTextCommand.Prepare):PluginOutcome {
        val result=broker.request(command);return if(result is QuickTextReply.Prepared)apply(result) else message(result)
    }
    private suspend fun apply(prepared:QuickTextReply.Prepared):PluginOutcome {
        val result=broker.request(QuickTextCommand.Commit(prepared.value.token))
        if(result is QuickTextReply.Applied) {broker.request(QuickTextCommand.OpenUi(QuickUiTarget.MANAGE));return PluginOutcome.Message("快捷文本已更新")}
        if(result is QuickTextReply.Failure&&result.code==QuickTextError.CONFIRMATION_REQUIRED)broker.request(QuickTextCommand.OpenUi(QuickUiTarget.REVIEW,prepared.value.token))
        return message(result)
    }
    private fun message(reply:QuickTextReply)=PluginOutcome.Message((reply as? QuickTextReply.Failure)?.message ?: "操作已完成")
}
