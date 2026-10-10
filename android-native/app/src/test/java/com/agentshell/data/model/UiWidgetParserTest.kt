package com.agentshell.data.model
import org.junit.Assert.*
import org.junit.Test
class UiWidgetParserTest {
 @Test fun aWidgetDocumentSurvivesParsingAndHistoryReloadWithoutLosingScriptOrUnicode() {
  val html="<html><style>svg{color:purple}</style><script>const label='Revisão 🧪';</script></html>"
  val data=mapOf("role" to "assistant","timestamp" to "2026-10-10T00:00:00Z","blocks" to listOf(mapOf("type" to "ui_widget","id" to "widget-one","title" to "Chart","html" to html)))
  val first=ChatMessageParser.parse(data);val reloaded=ChatMessageParser.parse(data)
  assertEquals(first.id,reloaded.id);assertEquals(ChatBlockType.UI_WIDGET,first.blocks.single().blockType)
  assertEquals(html,first.blocks.single().html);assertEquals("widget-one",first.blocks.single().id)
  assertEquals("assistant",first.type)
 }
}
