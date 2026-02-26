package com.example.myapplication.agent

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

/**
 * Unit tests for ToolManager
 *
 * Tests tool call parsing, system prompt generation, and parameter handling.
 *
 * Note: Tests use returnDefaultValues = true in gradle to handle android.util.Log calls
 */
class ToolManagerTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    private lateinit var toolManager: ToolManager

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        // Setup mock SharedPreferences
        `when`(mockContext.getSharedPreferences(anyString(), anyInt()))
            .thenReturn(mockSharedPreferences)
        `when`(mockSharedPreferences.getString(anyString(), any())).thenReturn(null)
        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.remove(anyString())).thenReturn(mockEditor)
        `when`(mockEditor.apply()).then { }

        toolManager = ToolManager(mockContext)
    }

    // ========== parseToolCalls Tests ==========

    @Test
    fun `parseToolCalls parses tool block format`() {
        val response = """
            Thinking about what to do...
            ```tool
            click(540, 1200)
            ```
        """.trimIndent()

        val calls = toolManager.parseToolCalls(response)

        assertEquals(1, calls.size)
        assertEquals("click", calls[0].name)
        assertEquals(540, calls[0].parameters["x"])
        assertEquals(1200, calls[0].parameters["y"])
    }

    @Test
    fun `parseToolCalls parses inline format`() {
        val response = "Let me click on that button. click(540, 1200)"

        val calls = toolManager.parseToolCalls(response)

        assertEquals(1, calls.size)
        assertEquals("click", calls[0].name)
    }

    @Test
    fun `parseToolCalls parses quoted string parameters`() {
        val response = """type("Hello World")"""

        val calls = toolManager.parseToolCalls(response)

        assertEquals(1, calls.size)
        assertEquals("type", calls[0].name)
        assertEquals("Hello World", calls[0].parameters["text"])
    }

    @Test
    fun `parseToolCalls parses single quoted string parameters`() {
        val response = """open_app('微信')"""

        val calls = toolManager.parseToolCalls(response)

        assertEquals(1, calls.size)
        assertEquals("open_app", calls[0].name)
        assertEquals("微信", calls[0].parameters["package_name"])
    }

    @Test
    fun `parseToolCalls parses swipe with direction parameter`() {
        val response = """swipe("up", 500)"""

        val calls = toolManager.parseToolCalls(response)

        assertEquals(1, calls.size)
        assertEquals("swipe", calls[0].name)
        assertEquals("up", calls[0].parameters["direction"])
        assertEquals(500, calls[0].parameters["distance"])
    }

    @Test
    fun `parseToolCalls parses tool with no parameters`() {
        val response = "back()"

        val calls = toolManager.parseToolCalls(response)

        assertEquals(1, calls.size)
        assertEquals("back", calls[0].name)
        assertTrue(calls[0].parameters.isEmpty())
    }

    @Test
    fun `parseToolCalls returns empty list for no tool calls`() {
        val response = "This is just a regular message without any tool calls."

        val calls = toolManager.parseToolCalls(response)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `parseToolCalls ignores unknown tool names`() {
        val response = "unknown_tool(123)"

        val calls = toolManager.parseToolCalls(response)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `parseToolCalls handles comma in quoted string`() {
        val response = """type("Hello, World!")"""

        val calls = toolManager.parseToolCalls(response)

        assertEquals(1, calls.size)
        assertEquals("type", calls[0].name)
        assertEquals("Hello, World!", calls[0].parameters["text"])
    }

    @Test
    fun `parseToolCalls parses finish with summary`() {
        val response = """finish("Task completed successfully")"""

        val calls = toolManager.parseToolCalls(response)

        assertEquals(1, calls.size)
        assertEquals("finish", calls[0].name)
        assertEquals("Task completed successfully", calls[0].parameters["summary"])
    }

    @Test
    fun `parseToolCalls parses wait with milliseconds`() {
        val response = "wait(2000)"

        val calls = toolManager.parseToolCalls(response)

        assertEquals(1, calls.size)
        assertEquals("wait", calls[0].name)
        assertEquals(2000, calls[0].parameters["ms"])
    }

    @Test
    fun `parseToolCalls parses drag with four parameters`() {
        val response = "drag(100, 500, 100, 1500)"

        val calls = toolManager.parseToolCalls(response)

        assertEquals(1, calls.size)
        assertEquals("drag", calls[0].name)
        assertEquals(100, calls[0].parameters["start_x"])
        assertEquals(500, calls[0].parameters["start_y"])
        assertEquals(100, calls[0].parameters["end_x"])
        assertEquals(1500, calls[0].parameters["end_y"])
    }

    // ========== generateSystemPrompt Tests ==========

    @Test
    fun `generateSystemPrompt returns default when no custom prompt`() {
        `when`(mockSharedPreferences.getString("custom_system_prompt", null)).thenReturn(null)

        val prompt = toolManager.generateSystemPrompt()

        assertNotNull(prompt)
        assertTrue(prompt.contains("Android")) // Should contain Android reference
    }

    @Test
    fun `generateSystemPrompt returns custom prompt when set`() {
        val customPrompt = "Custom system prompt for testing"
        `when`(mockSharedPreferences.getString("custom_system_prompt", null)).thenReturn(customPrompt)

        val prompt = toolManager.generateSystemPrompt()

        assertEquals(customPrompt, prompt)
    }

    @Test
    fun `generateSystemPrompt returns default when custom prompt is blank`() {
        `when`(mockSharedPreferences.getString("custom_system_prompt", null)).thenReturn("")

        val prompt = toolManager.generateSystemPrompt()

        assertNotNull(prompt)
        assertTrue(prompt.isNotEmpty())
    }

    // ========== getDefaultSystemPrompt Tests ==========

    @Test
    fun `getDefaultSystemPrompt contains expected rules`() {
        val prompt = toolManager.getDefaultSystemPrompt()

        assertTrue(prompt.contains("open_app"))
        assertTrue(prompt.contains("reply"))
        assertTrue(prompt.contains("capture_screen"))
        assertTrue(prompt.contains("finish"))
    }

    @Test
    fun `getDefaultSystemPrompt contains common app packages`() {
        val prompt = toolManager.getDefaultSystemPrompt()

        assertTrue(prompt.contains("com.tencent.mm")) // WeChat
        assertTrue(prompt.contains("com.ss.android.lark")) // Feishu
    }

    // ========== getAvailableTools Tests ==========

    @Test
    fun `getAvailableTools returns nonEmpty list`() {
        val tools = toolManager.getAvailableTools()

        assertTrue(tools.isNotEmpty())
    }

    @Test
    fun `getAvailableTools contains click tool`() {
        val tools = toolManager.getAvailableTools()

        assertTrue(tools.any { it.name == "click" })
    }

    @Test
    fun `getAvailableTools contains swipe tool`() {
        val tools = toolManager.getAvailableTools()

        assertTrue(tools.any { it.name == "swipe" })
    }

    @Test
    fun `getAvailableTools contains type tool`() {
        val tools = toolManager.getAvailableTools()

        assertTrue(tools.any { it.name == "type" })
    }

    @Test
    fun `getAvailableTools contains navigation tools`() {
        val tools = toolManager.getAvailableTools()

        assertTrue(tools.any { it.name == "back" })
        assertTrue(tools.any { it.name == "home" })
    }

    @Test
    fun `getAvailableTools contains control tools`() {
        val tools = toolManager.getAvailableTools()

        assertTrue(tools.any { it.name == "finish" })
        assertTrue(tools.any { it.name == "reply" })
        assertTrue(tools.any { it.name == "wait" })
    }

    // ========== Tool Definition Tests ==========

    @Test
    fun `click tool has correct parameters`() {
        val tools = toolManager.getAvailableTools()
        val clickTool = tools.find { it.name == "click" }

        assertNotNull(clickTool)
        assertEquals(2, clickTool!!.parameters.size)
        assertTrue(clickTool.parameters.any { it.name == "x" })
        assertTrue(clickTool.parameters.any { it.name == "y" })
    }

    @Test
    fun `swipe tool has enum for direction`() {
        val tools = toolManager.getAvailableTools()
        val swipeTool = tools.find { it.name == "swipe" }

        assertNotNull(swipeTool)
        val directionParam = swipeTool!!.parameters.find { it.name == "direction" }
        assertNotNull(directionParam)
        assertNotNull(directionParam!!.enum)
        assertTrue(directionParam.enum!!.contains("up"))
        assertTrue(directionParam.enum!!.contains("down"))
        assertTrue(directionParam.enum!!.contains("left"))
        assertTrue(directionParam.enum!!.contains("right"))
    }

    // ========== saveCustomSystemPrompt Tests ==========

    @Test
    fun `saveCustomSystemPrompt stores prompt`() {
        val customPrompt = "New custom prompt"

        toolManager.saveCustomSystemPrompt(customPrompt)

        verify(mockEditor).putString("custom_system_prompt", customPrompt)
        verify(mockEditor).apply()
    }

    // ========== resetToDefault Tests ==========

    @Test
    fun `resetToDefault removes custom prompt`() {
        toolManager.resetToDefault()

        verify(mockEditor).remove("custom_system_prompt")
        verify(mockEditor).apply()
    }

    // ========== isUsingCustomPrompt Tests ==========

    @Test
    fun `isUsingCustomPrompt returns false when no custom prompt`() {
        `when`(mockSharedPreferences.getString("custom_system_prompt", null)).thenReturn(null)

        assertFalse(toolManager.isUsingCustomPrompt())
    }

    @Test
    fun `isUsingCustomPrompt returns true when custom prompt exists`() {
        `when`(mockSharedPreferences.getString("custom_system_prompt", null)).thenReturn("Custom prompt")

        assertTrue(toolManager.isUsingCustomPrompt())
    }
}
