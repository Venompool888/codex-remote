package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadProjectionTest {
    @Test fun workspaceLabelsRemainSeparateFromRoutingIdentity() {
        val reference = "remote-workspace://" + "a".repeat(64)
        val threads = ThreadProjection.threads(JSONObject().put("data", JSONArray()
            .put(JSONObject().put("id", "one").put("cwd", reference).put("cwdName", "project"))
            .put(JSONObject().put("id", "two").put("cwd", "remote-workspace://" + "b".repeat(64)).put("cwdName", "project"))))
        assertEquals(listOf("project", "project"), threads.map { it.cwdName })
        assertEquals(2, threads.map { it.cwd }.distinct().size)
        assertEquals(reference, threads.first().cwd)
        assertEquals("Workspace", workspaceDisplayName(reference))
        assertEquals("Workspace", workspaceDisplayName(""))
        assertEquals("Workspace", workspaceDisplayName("  "))
        assertEquals("root", workspaceDisplayName("/"))
        assertEquals("Workspace", workspaceDisplayName(reference, "/private/host/project"))
        assertEquals("Workspace", workspaceDisplayName(reference, "bad\nlabel"))
        assertEquals("project", workspaceDisplayName("/legacy/project/", "ignored"))
    }
    @Test fun opaqueFileNamesDoNotReplaceIdentityOrMergeSameNamedFiles() {
        fun change(id: String) = JSONObject().put("path", "remote-path://$id")
            .put("pathName", "main.py").put("kind", JSONObject().put("type", "update"))
            .put("diff", "@@ -1 +1 @@\n-old\n+new")
        val item = ThreadProjection.projectItem(JSONObject().put("id", "files").put("type", "fileChange")
            .put("status", "completed").put("changes", JSONArray().put(change("a".repeat(64))).put(change("b".repeat(64)))))!!
        assertEquals(2, item.changedFiles.size)
        assertEquals(2, item.fileDiffs.size)
        assertEquals(2, item.fileChanges.size)
        assertEquals(listOf("main.py", "main.py"), item.fileChanges.map { it.displayName })
        assertEquals(2, item.fileChanges.map { it.path }.distinct().size)
        assertFalse(item.text.contains("remote-path"))
        assertTrue(item.label.contains("2 files"))
        val single = ThreadProjection.projectItem(JSONObject().put("id", "one").put("type", "fileChange")
            .put("status", "completed").put("changes", JSONArray().put(change("a".repeat(64)))))!!
        assertTrue(single.label.contains("main.py"))
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject().put("id", "turn")
            .put("status", "completed").put("items", JSONArray().put(JSONObject().put("id", "files")
                .put("type", "fileChange").put("status", "completed")
                .put("changes", JSONArray().put(change("a".repeat(64))).put(change("b".repeat(64))))))))
        val grouped = ThreadProjection.timeline(thread).single()
        assertEquals(2, grouped.fileChanges.size)
        assertEquals(listOf("main.py", "main.py"), grouped.fileChanges.map { it.displayName })
        assertEquals(2, grouped.fileChanges.map { it.path }.distinct().size)
    }

    @Test fun opaquePathDisplayRejectsMissingOrPathShapedLabelsButPreservesLegacyPaths() {
        val value = JSONObject().put("path", "remote-path://" + "a".repeat(64))
        assertEquals("File", value.displayPath())
        for (label in listOf("/private/host/name", "bad\nname", "")) {
            assertEquals("File", value.put("pathName", label).displayPath())
        }
        assertEquals("src/main.py", JSONObject().put("path", "src/main.py").put("pathName", "ignored").displayPath())
    }

    @Test fun sentAttachmentsKeepCardsSeparateFromUserProse() {
        val thread = JSONObject("""{"id":"thread","turns":[{"id":"turn","status":"completed","items":[{"id":"user","type":"userMessage","content":[{"type":"text","text":"Attached file: report.pdf","remoteAttachment":{"id":"12345678-1234-4234-8234-123456789abc","name":"report.pdf","kind":"file"}},{"type":"text","text":"Summarize this report."}]}]}]}""")
        val item = ThreadProjection.timeline(thread).first { it.kind == TimelineItem.Kind.USER }
        assertEquals("Summarize this report.", item.text)
        assertEquals("report.pdf", item.attachments.single().name)
        assertEquals("12345678-1234-4234-8234-123456789abc", item.attachments.single().id)
    }

    @Test
    fun workingActivityCannotCollapseUntilTurnFinishes() {
        val child = TimelineItem("tool", "Generated image", "", TimelineItem.Kind.TOOL)
        val working = TimelineItem(
            "activity",
            "Working",
            "",
            TimelineItem.Kind.ACTIVITY_GROUP,
            children = listOf(child),
            active = true,
        )

        assertFalse(ThreadProjection.activityGroupCanCollapse(working))
        assertTrue(ThreadProjection.activityGroupCanCollapse(working.copy(active = false)))
        assertFalse(ThreadProjection.activityGroupCanCollapse(working.copy(active = false, children = emptyList())))
    }

    @Test
    fun legacyImageDoesNotExposePrivatePathAndExplainsPreviewUnavailable() {
        val item = ThreadProjection.projectItem(JSONObject()
            .put("id", "user-with-attachment")
            .put("type", "userMessage")
            .put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "  Is memory usage high?  "))
                .put(JSONObject().put("type", "localImage").put("path", "/tmp/screenshot.png"))
                .put(JSONObject().put("type", "text").put("text", "   "))))

        assertEquals("Is memory usage high?\nImage attachment · preview requires a newer host", item?.text)
        assertFalse(item!!.text.contains("/tmp/"))
    }

    @Test
    fun generatedImageKeepsItsRemoteAssetPath() {
        val item = ThreadProjection.projectItem(JSONObject()
            .put("id", "generated-image")
            .put("type", "imageGeneration")
            .put("status", "completed")
            .put("path", "/root/.codex/generated_images/thread/image.png"))

        assertEquals("/root/.codex/generated_images/thread/image.png", item?.imagePath)
        assertEquals("Generated image", item?.label)
    }

    @Test
    fun activitySectionsPreserveEveryProtocolItemInOrder() {
        val commentaryOne = TimelineItem("note-1", "Codex", "First note", TimelineItem.Kind.COMMENTARY)
        val commentaryTwo = TimelineItem("note-2", "Codex", "Second note", TimelineItem.Kind.COMMENTARY)
        val emptyReasoning = TimelineItem("reasoning-empty", "Thinking", "", TimelineItem.Kind.REASONING)
        val skill = TimelineItem("skill", "Read Local Host Profile skill", "", TimelineItem.Kind.COMMAND,
            toolStyle = TimelineItem.ToolStyle.SKILL)
        val read = TimelineItem("read", "Ran sed", "", TimelineItem.Kind.COMMAND,
            toolStyle = TimelineItem.ToolStyle.READ)
        val run = TimelineItem("run", "Ran commands", "", TimelineItem.Kind.COMMAND)
        val edit = TimelineItem("edit", "Modified file", "", TimelineItem.Kind.FILE_CHANGE)
        val image = TimelineItem("image", "Viewed image", "", TimelineItem.Kind.TOOL)

        val sections = ThreadProjection.activitySections(listOf(
            commentaryOne,
            emptyReasoning,
            skill,
            read,
            run,
            commentaryTwo,
            edit,
            read.copy(id = "read-2"),
            run.copy(id = "run-2"),
            image,
        ))

        assertEquals(
            listOf("note-1", "skill", "read", "run", "note-2", "edit", "read-2", "run-2", "image"),
            sections.map { it.id },
        )
        assertEquals(listOf(
            "First note",
            "Read Local Host Profile skill",
            "Ran sed",
            "Ran commands",
            "Second note",
            "Modified file",
            "Ran sed",
            "Ran commands",
            "Viewed image",
        ), sections.map { if (it.kind == TimelineItem.Kind.COMMENTARY) it.text else it.label })
    }

    @Test
    fun skillReadsUseDesktopStyleSemanticLabel() {
        val item = ThreadProjection.projectItem(JSONObject()
            .put("id", "skill-read")
            .put("type", "commandExecution")
            .put("status", "completed")
            .put("command", "sed -n '1,240p' /root/.codex/skills/local-host-profile/SKILL.md"))

        assertEquals("Read Local Host Profile skill", item?.label)
        assertEquals(TimelineItem.ToolStyle.SKILL, item?.toolStyle)
    }

    @Test
    fun commandBatchesKeepTheirDesktopCommandTitle() {
        val command = "pid=3901114 printf '%s\\n' '[host memory]'; free -h"
        val item = ThreadProjection.projectItem(JSONObject()
            .put("id", "batch")
            .put("type", "commandExecution")
            .put("status", "completed")
            .put("command", command))

        assertEquals("Ran $command", item?.label)
    }

    @Test
    fun chainedReadCommandKeepsItsDesktopCommandTitle() {
        val command = "sed -n '1,260p' /root/current-state.md && ps -eo pid,ppid,rss,args"
        val item = ThreadProjection.projectItem(JSONObject()
            .put("id", "read-and-inspect")
            .put("type", "commandExecution")
            .put("status", "completed")
            .put("command", command))

        assertEquals("Ran $command", item?.label)
    }

    @Test
    fun shellTransportOpeningQuoteIsNotShownInDesktopTitle() {
        val command = "/bin/bash -lc \"pid=3901114\nprintf '%s\\n' '[host memory]'\ncat '/proc/'${'$'}pid'/status'"

        assertEquals("pid=3901114", ThreadProjection.displayCommand(command))
    }

    @Test
    fun transportWrapperIsUnwrappedAndHiddenBesideItsRealCommand() {
        val command = "sed -n '1,240p' /root/.codex/skills/local-host-profile/SKILL.md"
        val shellCommand = "/bin/bash -lc \"$command\""
        val wrapper = "const r = await tools.exec_command(${JSONObject().put("cmd", command)}); text(r.output)"
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-wrapper")
            .put("status", "completed")
                .put("items", JSONArray()
                .put(JSONObject().put("id", "real").put("type", "commandExecution")
                    .put("status", "completed").put("command", shellCommand))
                .put(JSONObject().put("id", "wrapper").put("type", "commandExecution")
                    .put("status", "completed").put("command", wrapper))
                .put(JSONObject().put("id", "final").put("type", "agentMessage")
                    .put("phase", "final_answer").put("text", "Done.")))))

        val activity = ThreadProjection.timeline(thread).first()
        assertEquals(1, activity.children.count { it.kind == TimelineItem.Kind.COMMAND })
        assertEquals("Read Local Host Profile skill", activity.children.single().label)
        assertEquals(command, ThreadProjection.canonicalCommand(wrapper))
    }

    @Test
    fun codeModeOrchestrationHasAConciseTitleAndRetainsInspectableCode() {
        val code = "const matches = ALL_TOOLS.filter(x => /ask_pixel_form$/.test(x.name)); await tools[matches[0].name]({});"
        for ((status, label) in listOf("inProgress" to "Running tools", "completed" to "Ran tools", "failed" to "Tools failed")) {
            val item = ThreadProjection.projectItem(JSONObject().put("id", "code-mode")
                .put("type", "commandExecution").put("status", status).put("command", code)
                .put("aggregatedOutput", "MCP result"))!!
            assertEquals(label, item.label)
            assertEquals(code, item.rawCommand)
            assertEquals("MCP result", item.text)
        }
        val ordinary = "printf '%s' 'ALL_TOOLS tools.example'"
        val shell = ThreadProjection.projectItem(JSONObject().put("id", "shell")
            .put("type", "commandExecution").put("status", "completed").put("command", ordinary))!!
        assertEquals("Ran $ordinary", shell.label)
    }

    @Test
    fun sparseCommandRowsStillReceiveAFriendlySummary() {
        val item = ThreadProjection.projectItem(JSONObject()
            .put("id", "sparse-command")
            .put("type", "commandExecution")
            .put("status", "completed"))

        assertEquals("Ran commands", item?.label)
    }

    @Test
    fun listAndDetailUseHostPreviewBeforeLatestUserTask() {
        fun user(id: String, text: String) = JSONObject()
            .put("id", id)
            .put("type", "userMessage")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        val thread = JSONObject()
            .put("name", JSONObject.NULL)
            .put("preview", "First task")
            .put("turns", JSONArray()
                .put(JSONObject().put("items", JSONArray().put(user("u1", "First task"))))
                .put(JSONObject().put("items", JSONArray().put(user("u2", "Newest shell cleanup task")))))

        assertEquals("First task", ThreadProjection.threadTitle(thread))
        thread.put("id", "same-thread")
        assertEquals(ThreadProjection.threadTitle(thread),
            ThreadProjection.threads(JSONObject().put("data", JSONArray().put(thread))).single().title)
        thread.remove("preview")
        assertEquals("Newest shell cleanup task", ThreadProjection.threadTitle(thread))
    }

    @Test
    fun explicitThreadNameWinsOverLatestUserTask() {
        val thread = JSONObject()
            .put("name", "Pinned title")
            .put("turns", JSONArray().put(JSONObject().put("items", JSONArray().put(JSONObject()
                .put("id", "u1")
                .put("type", "userMessage")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Newest task")))))))

        assertEquals("Pinned title", ThreadProjection.threadTitle(thread))
    }

    @Test
    fun nullThreadNameFallsBackToPreview() {
        val result = JSONObject().put("data", JSONArray().put(JSONObject()
            .put("id", "thread-1")
            .put("name", JSONObject.NULL)
            .put("preview", "Real title")
            .put("cwd", "/root")))

        assertEquals("Real title", ThreadProjection.threads(result).single().title)
    }

    @Test
    fun threadListProjectsActivityAndUpdatedTime() {
        val result = JSONObject().put("data", JSONArray()
            .put(JSONObject()
                .put("id", "thread-active")
                .put("preview", "Working")
                .put("cwd", "/root")
                .put("updatedAt", 1_723_456_789L)
                .put("status", JSONObject().put("type", "active")))
            .put(JSONObject()
                .put("id", "thread-idle")
                .put("preview", "Done")
                .put("cwd", "/root")
                .put("updatedAt", 1_723_450_000L)
                .put("status", JSONObject().put("type", "idle"))))

        val threads = ThreadProjection.threads(result)

        assertTrue(threads.first().isRunning)
        assertFalse(threads.last().isRunning)
        assertEquals(1_723_456_789L, threads.first().updatedAtEpochSeconds)
    }

    @Test
    fun relativeAgeUsesCompactUnits() {
        val now = 2_000_000L

        assertEquals("now", ThreadProjection.relativeAge(now - 30L, now))
        assertEquals("8m", ThreadProjection.relativeAge(now - 8L * 60L, now))
        assertEquals("2h", ThreadProjection.relativeAge(now - 2L * 3_600L, now))
        assertEquals("3d", ThreadProjection.relativeAge(now - 3L * 86_400L, now))
        assertEquals("—", ThreadProjection.relativeAge(null, now))
    }

    @Test
    fun projectsMessagesAndActivity() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-1")
            .put("status", "completed")
            .put("durationMs", 112_000)
            .put("items", JSONArray()
                .put(JSONObject().put("id", "u1").put("type", "userMessage")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Fix it"))))
                .put(JSONObject().put("id", "c1").put("type", "commandExecution")
                    .put("status", "completed").put("command", "npm test").put("aggregatedOutput", "passed"))
                .put(JSONObject().put("id", "a1").put("type", "agentMessage").put("text", "Done")))))

        val items = ThreadProjection.timeline(thread)
        assertEquals(listOf("You", "Completed", "Codex"), items.map { it.label })
        assertEquals("Fix it", items.first().text)
        assertEquals(TimelineItem.Kind.ACTIVITY_GROUP, items[1].kind)
        assertEquals(TimelineItem.Kind.COMMAND, items[1].children.single().kind)
        assertEquals("Ran npm test", items[1].children.single().label)
        assertEquals(112_000L, items[1].durationMs)
    }

    @Test
    fun runningTurnShowsLiveActivityState() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-live")
            .put("status", "inProgress")
            .put("startedAt", 100)
            .put("items", JSONArray()
                .put(JSONObject().put("id", "u1").put("type", "userMessage")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Check it"))))
                .put(JSONObject().put("id", "r1").put("type", "reasoning")
                    .put("summary", JSONArray().put("Inspecting the project"))
                    .put("content", JSONArray())))))

        val items = ThreadProjection.timeline(thread)
        assertEquals(listOf("You", "Working"), items.map { it.label })
        assertEquals(TimelineItem.Kind.ACTIVITY_GROUP, items.last().kind)
        assertEquals("Inspecting the project", items.last().text)
        assertTrue(items.last().active)
        assertTrue(items.last().children.single().active)
        assertFalse(items.first().active)
    }

    @Test
    fun timestampsProvideDurationFallback() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-timestamps")
            .put("status", "completed")
            .put("startedAt", 10)
            .put("completedAt", 75)
            .put("items", JSONArray().put(JSONObject()
                .put("id", "a1").put("type", "agentMessage").put("text", "Done")))))

        assertEquals("Codex", ThreadProjection.timeline(thread).single().label)
    }

    @Test
    fun commentaryReasoningAndToolsStayInProtocolOrder() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-phases")
            .put("status", "completed")
            .put("durationMs", 132_000)
            .put("items", JSONArray()
                .put(JSONObject().put("id", "u1").put("type", "userMessage")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Diagnose it"))))
                .put(JSONObject().put("id", "a1").put("type", "agentMessage")
                    .put("phase", "commentary").put("text", "I’ll inspect the logs."))
                .put(JSONObject().put("id", "r1").put("type", "reasoning")
                    .put("summary", JSONArray().put("Tracing the failure")).put("content", JSONArray()))
                .put(JSONObject().put("id", "c1").put("type", "commandExecution")
                    .put("status", "completed").put("command", "rg error logs").put("aggregatedOutput", "one match"))
                .put(JSONObject().put("id", "a2").put("type", "agentMessage")
                    .put("phase", "final_answer").put("text", "The failure is fixed.")))))

        val items = ThreadProjection.timeline(thread)
        assertEquals(
            listOf(
                TimelineItem.Kind.USER,
                TimelineItem.Kind.ACTIVITY_GROUP,
                TimelineItem.Kind.ASSISTANT,
            ),
            items.map { it.kind },
        )
        assertEquals(
            listOf(TimelineItem.Kind.COMMENTARY, TimelineItem.Kind.REASONING, TimelineItem.Kind.COMMAND),
            items[1].children.map { it.kind },
        )
        assertEquals(132_000L, items[1].durationMs)
        assertEquals("The failure is fixed.", items.last().text)
    }

    @Test
    fun completedTurnPreservesExplanationToolResultSequence() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-sandwich")
            .put("status", "completed")
            .put("items", JSONArray()
                .put(JSONObject().put("id", "u1").put("type", "userMessage")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Inspect it"))))
                .put(JSONObject().put("id", "a1").put("type", "agentMessage")
                    .put("phase", "commentary").put("text", "I’ll inspect the configuration."))
                .put(JSONObject().put("id", "c1").put("type", "commandExecution")
                    .put("status", "completed").put("command", "rg timeout config").put("aggregatedOutput", "timeout=30"))
                .put(JSONObject().put("id", "a2").put("type", "agentMessage")
                    .put("phase", "commentary").put("text", "The timeout is 30 seconds; I’ll verify its caller."))
                .put(JSONObject().put("id", "a3").put("type", "agentMessage")
                    .put("phase", "final_answer").put("text", "The configuration is valid.")))))

        val items = ThreadProjection.timeline(thread)

        assertEquals(
            listOf(
                TimelineItem.Kind.USER,
                TimelineItem.Kind.ACTIVITY_GROUP,
                TimelineItem.Kind.ASSISTANT,
            ),
            items.map { it.kind },
        )
        assertEquals(
            listOf(
                "Inspect it",
                "",
                "The configuration is valid.",
            ),
            items.map { it.text },
        )
        assertEquals(
            listOf("I’ll inspect the configuration.", "timeout=30", "The timeout is 30 seconds; I’ll verify its caller."),
            items[1].children.map { it.text },
        )
    }

    @Test
    fun legacyMessagesRemainAssistantMessagesInProtocolOrder() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-legacy")
            .put("status", "completed")
            .put("durationMs", 2_000)
            .put("items", JSONArray()
                .put(JSONObject().put("id", "a1").put("type", "agentMessage").put("text", "Checking now"))
                .put(JSONObject().put("id", "a2").put("type", "agentMessage").put("text", "Done")))))

        val items = ThreadProjection.timeline(thread)
        assertEquals(TimelineItem.Kind.ASSISTANT, items.first().kind)
        assertEquals("Checking now", items.first().text)
        assertEquals("Done", items.last().text)
    }

    @Test
    fun runningItemsStayVisibleAndLatestWorkItemIsActive() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-live-execution")
            .put("status", "inProgress")
            .put("items", JSONArray()
                .put(JSONObject().put("id", "r1").put("type", "reasoning")
                    .put("summary", JSONArray().put("Checking the remote host")))
                .put(JSONObject().put("id", "w1").put("type", "webSearch").put("query", "Codex docs"))
                .put(JSONObject().put("id", "a1").put("type", "agentMessage")
                    .put("phase", "commentary").put("text", "The host is compatible."))
                .put(JSONObject().put("id", "c1").put("type", "commandExecution")
                    .put("status", "inProgress").put("command", "apt-get install nodejs")))))

        val items = ThreadProjection.timeline(thread)
        assertEquals(listOf(TimelineItem.Kind.ACTIVITY_GROUP), items.map { it.kind })
        assertEquals(
            listOf(TimelineItem.Kind.REASONING, TimelineItem.Kind.TOOL, TimelineItem.Kind.COMMENTARY, TimelineItem.Kind.COMMAND),
            items.single().children.map { it.kind },
        )
        assertEquals("Running apt-get install nodejs", items.single().text)
        assertEquals(listOf(false, false, false, true), items.single().children.map { it.active })
        assertEquals(2, items.single().stepCurrent)
        assertEquals(0, items.single().stepTotal)
    }

    @Test
    fun laterCommentaryDoesNotReplaceTheConcreteActionTicker() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-semantic-ticker")
            .put("status", "inProgress")
            .put("items", JSONArray()
                .put(JSONObject().put("id", "c1").put("type", "commandExecution")
                    .put("status", "completed").put("command", "pwd").put("aggregatedOutput", "/root"))
                .put(JSONObject().put("id", "a1").put("type", "agentMessage")
                    .put("phase", "commentary").put("text", "The first check was blocked by the sandbox.")))))

        val execution = ThreadProjection.timeline(thread).single()

        assertEquals("Ran pwd", execution.text)
        assertEquals(listOf(false, false), execution.children.map { it.active })
        assertEquals(1, execution.stepCurrent)
        assertEquals(0, execution.stepTotal)
    }

    @Test
    fun finalAnswerStartSettlesCardBeforeTurnCompletionArrives() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-final-start")
            .put("status", "inProgress")
            .put("items", JSONArray()
                .put(JSONObject().put("id", "c1").put("type", "commandExecution")
                    .put("status", "completed").put("command", "pwd").put("aggregatedOutput", "/root"))
                .put(JSONObject().put("id", "a1").put("type", "agentMessage")
                    .put("phase", "final_answer").put("text", "Done")))))

        val items = ThreadProjection.timeline(thread)

        assertEquals(listOf(TimelineItem.Kind.ACTIVITY_GROUP, TimelineItem.Kind.ASSISTANT), items.map { it.kind })
        assertEquals("Completed", items.first().label)
        assertEquals("completed", items.first().phase)
        assertFalse(items.first().active)
        assertEquals(1, items.first().stepCurrent)
        assertEquals(0, items.first().stepTotal)
    }

    @Test
    fun mcpAppUsesIntegrationNameAndResultText() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-mcp")
            .put("status", "completed")
            .put("items", JSONArray().put(JSONObject()
                .put("id", "m1")
                .put("type", "mcpToolCall")
                .put("server", "codex_apps")
                .put("tool", "github_search")
                .put("status", "completed")
                .put("arguments", JSONObject().put("query", "codex"))
                .put("appContext", JSONObject().put("appName", "GitHub"))
                .put("result", JSONObject().put("content", JSONArray().put(JSONObject()
                    .put("type", "text").put("text", "Found the repository"))))))))

        val items = ThreadProjection.timeline(thread)

        assertEquals("Completed", items.single().label)
        assertEquals(TimelineItem.Kind.ACTIVITY_GROUP, items.single().kind)
        assertEquals("Used GitHub integration", items.single().children.single().label)
        assertEquals("Found the repository", items.single().children.single().text)
    }

    @Test
    fun completedActivityNeverKeepsTheLiveShimmerState() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-done")
            .put("status", "completed")
            .put("durationMs", 3_000)
            .put("items", JSONArray()
                .put(JSONObject().put("id", "r1").put("type", "reasoning")
                    .put("summary", JSONArray().put("Checking files")))
                .put(JSONObject().put("id", "c1").put("type", "commandExecution")
                    .put("status", "completed").put("command", "pwd").put("aggregatedOutput", "/root"))
                .put(JSONObject().put("id", "a1").put("type", "agentMessage")
                    .put("phase", "final_answer").put("text", "Done")))))

        val items = ThreadProjection.timeline(thread)
        assertFalse(items.any { it.active })
        assertEquals(listOf(TimelineItem.Kind.ACTIVITY_GROUP, TimelineItem.Kind.ASSISTANT), items.map { it.kind })
        assertFalse(items.first().children.any { it.active })
    }

    @Test
    fun completedTurnUsesUuidV7ItemTimesWhenDurationFieldsAreMissing() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "00000000-03e8-7000-8000-000000000000")
            .put("status", "completed")
            .put("items", JSONArray()
                .put(JSONObject().put("id", "00000000-1388-7000-8000-000000000000")
                    .put("type", "reasoning").put("summary", JSONArray().put("Checking files")))
                .put(JSONObject().put("id", "00000001-01d0-7000-8000-000000000000")
                    .put("type", "fileChange").put("status", "failed")
                    .put("changes", JSONArray().put(JSONObject().put("path", "/root/test.txt"))))
                .put(JSONObject().put("id", "00000001-01d0-7000-8000-000000000001")
                    .put("type", "agentMessage").put("phase", "final_answer").put("text", "No files changed.")))))

        val items = ThreadProjection.timeline(thread)
        assertEquals(listOf(TimelineItem.Kind.ACTIVITY_GROUP, TimelineItem.Kind.ASSISTANT), items.map { it.kind })
        assertEquals("File change failed", items.first().children[1].label)
        assertEquals(65_000L, items.first().durationMs)
        assertFalse(items.first().children.any { it.active })
    }

    @Test
    fun fileChangesAggregatePathsAndDiffLinesIntoExecutionCard() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-diff")
            .put("status", "completed")
            .put("items", JSONArray()
                .put(JSONObject().put("id", "f1").put("type", "fileChange").put("status", "completed")
                    .put("changes", JSONArray()
                        .put(JSONObject().put("path", "a.kt").put("patch", "@@\n-old\n+new\n+extra"))
                        .put(JSONObject().put("path", "b.kt").put("additions", 3).put("deletions", 2)))))))

        val execution = ThreadProjection.timeline(thread).single()

        assertEquals(TimelineItem.Kind.ACTIVITY_GROUP, execution.kind)
        assertEquals(2, execution.filesChanged)
        assertEquals(5, execution.additions)
        assertEquals(3, execution.deletions)
        assertEquals(FileDiffStat(2, 1), execution.fileDiffs["a.kt"])
        assertEquals(FileDiffStat(3, 2), execution.fileDiffs["b.kt"])
    }

    @Test
    fun readAndSearchToolsReceiveDedicatedSemanticStyles() {
        val read = ThreadProjection.projectItem(JSONObject()
            .put("id", "read-1")
            .put("type", "dynamicToolCall")
            .put("tool", "read_file")
            .put("status", "completed"))
        val search = ThreadProjection.projectItem(JSONObject()
            .put("id", "search-1")
            .put("type", "dynamicToolCall")
            .put("tool", "search_code")
            .put("status", "completed"))

        assertEquals(TimelineItem.ToolStyle.READ, read?.toolStyle)
        assertEquals(TimelineItem.ToolStyle.SEARCH, search?.toolStyle)
    }

    @Test
    fun addAndDeleteFileKindsCountRawContentLines() {
        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", "turn-raw-diff")
            .put("status", "completed")
            .put("items", JSONArray()
                .put(JSONObject().put("id", "f1").put("type", "fileChange").put("status", "completed")
                    .put("changes", JSONArray().put(JSONObject()
                        .put("path", "test.txt")
                        .put("kind", JSONObject().put("type", "add"))
                        .put("diff", "alpha\n"))))
                .put(JSONObject().put("id", "f2").put("type", "fileChange").put("status", "completed")
                    .put("changes", JSONArray().put(JSONObject()
                        .put("path", "test.txt")
                        .put("kind", JSONObject().put("type", "update"))
                        .put("diff", "@@ -1 +1,2 @@\n alpha\n+beta\n"))))
                .put(JSONObject().put("id", "f3").put("type", "fileChange").put("status", "completed")
                    .put("changes", JSONArray().put(JSONObject()
                        .put("path", "test.txt")
                        .put("kind", JSONObject().put("type", "delete"))
                        .put("diff", "alpha\nbeta\n")))))))

        val execution = ThreadProjection.timeline(thread).single()

        assertEquals(1, execution.filesChanged)
        assertEquals(2, execution.additions)
        assertEquals(2, execution.deletions)
        assertEquals(
            listOf("Created test.txt", "Modified test.txt", "Deleted test.txt"),
            execution.children.map { it.label },
        )
        assertEquals("+alpha", execution.children.first().fileChanges.single().patch)
    }
}
