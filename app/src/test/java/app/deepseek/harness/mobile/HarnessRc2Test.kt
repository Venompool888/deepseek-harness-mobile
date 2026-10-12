package app.deepseek.harness.mobile

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class HarnessRc2Test {
    @Test fun sessionAddressResolutionKeepsOrdinaryAndRequiresSubagentParent() {
        val ordinary = resolveHarnessSessionAddress("root-id", null, null)!!
        assertEquals("session", ordinary.kind)
        assertEquals("root-id", ordinary.toJson().getString("sessionId"))

        val child = resolveHarnessSessionAddress("child-id", "subagent", "parent-id")!!
        assertEquals("subagent", child.kind)
        assertEquals("parent-id", child.toJson().getString("parentSessionId"))
        assertEquals("child-id", child.toJson().getString("childSessionId"))
        assertEquals("unknown", child.toJson().getString("mode"))

        assertNull(resolveHarnessSessionAddress("child-id", "subagent", null))
        assertNull(resolveHarnessSessionAddress("child-id", "subagent", "  "))
        assertNull(resolveHarnessSessionAddress("", null, null))
    }

    @Test fun olderPageUsesTheResolvedSubagentAddress() {
        val child = resolveHarnessSessionAddress("child-id", "subagent", "parent-id")!!
        val state = HarnessSessionState(child)
        state.accept(JSONObject("""{"type":"snapshot","cursor":4,"records":[],"projections":{"asOfSeq":4,"values":{}},"assistantStream":{"revision":0}}"""))
        val request = state.pageRequest("child-id")
        assertEquals("subagent", request.getJSONObject("address").getString("kind"))
        assertEquals("parent-id", request.getJSONObject("address").getString("parentSessionId"))
        assertEquals("child-id", request.getJSONObject("address").getString("childSessionId"))
    }

    @Test fun durableAttemptDoesNotRemainAnActiveReply() {
        val state = HarnessSessionState()
        state.accept(JSONObject("""{"type":"snapshot","cursor":1,"records":[{"type":"event","event":{"seq":1,"time":100,"type":"assistant/attempt","data":{"turn":1,"step":1,"stream":[{"type":"text-chunks","time0":100,"index":0,"dt":[],"texts":["Incomplete prefix"]}]}}}],"projections":{"asOfSeq":1,"values":{}},"assistantStream":{"revision":0}}"""))
        val message = ChatProjection.fromHistory(state.events()).single()
        assertFalse(message.pending)
        assertEquals(ChatMessage.Role.ACTIVITY, message.role)
    }

    @Test fun olderPagesMergeWithoutReplacingLiveCursor() {
        val state = HarnessSessionState()
        state.accept(JSONObject("""{"type":"snapshot","cursor":10,"records":[{"type":"event","event":{"seq":10,"type":"turn/end","data":{}}}],"hasMore":true,"projections":{"asOfSeq":10,"values":{}},"assistantStream":{"revision":0}}"""))
        assertEquals(10L, state.pageRequest("s").getLong("throughSeq"))
        assertEquals(10L, state.pageRequest("s").getLong("beforeSeq"))
        state.prependPage(JSONObject("""{"records":[{"type":"event","event":{"seq":9,"type":"turn/start","data":{}}}],"hasMore":false}"""))
        state.accept(JSONObject("""{"type":"event","event":{"seq":11,"type":"turn/end","data":{}}}"""))
        assertEquals(3, state.events().length())
        assertFalse(state.hasMore)
    }

    @Test fun lostPromptReceiptRetainsDeduplicationIdentity() {
        val submission = HarnessPromptSubmission()
        val admissions = mutableSetOf<String>()
        val first = submission.begin("server", "session", "same text", "queue")
        admissions += first
        val retry = submission.begin("server", "session", "same text", "queue")
        admissions += retry
        assertEquals(1, admissions.size)
        submission.accepted(first)
        assertNotEquals(first, submission.begin("server", "session", "same text", "queue"))
    }

    @Test fun unconfirmedDraftIdentitySurvivesUnrelatedSubmissions() {
        val submission = HarnessPromptSubmission()
        val first = submission.begin("server", "session-a", "unconfirmed draft", "queue")
        val otherSession = submission.begin("server", "session-b", "other draft", "queue")
        submission.accepted(otherSession)
        assertEquals(first, submission.begin("server", "session-a", "unconfirmed draft", "queue"))

        val otherServer = submission.begin("another-server", "session-a", "unconfirmed draft", "queue")
        submission.accepted(otherServer)
        assertEquals(first, submission.begin("server", "session-a", "unconfirmed draft", "queue"))

        val editedDraft = submission.begin("server", "session-a", "edited draft", "queue")
        submission.accepted(editedDraft)
        assertEquals(first, submission.begin("server", "session-a", "unconfirmed draft", "queue"))
        submission.accepted(first)
        assertNotEquals(first, submission.begin("server", "session-a", "unconfirmed draft", "queue"))
    }

    @Test fun harnessCookiesAreIsolatedByAuthority() {
        val a = harnessCookieName("http://localhost:3080")
        val b = harnessCookieName("http://localhost:8080")
        assertEquals("$a=first; CF_Authorization=external", harnessCookies("http://localhost:3080", "$a=first; $b=second; CF_Authorization=external"))
        assertFalse(harnessCookies("http://localhost:8080", "$a=first")!!.contains("first"))
    }
    @Test fun newerProjectionWinsAcrossOpeningSnapshot() {
        val state = HarnessSessionState()
        state.projection("title", "newer", 11)
        state.accept(JSONObject("""{"type":"snapshot","cursor":10,"records":[],"projections":{"asOfSeq":10,"values":{"title":"baseline"}},"assistantStream":{"revision":0}}"""))
        state.projection("title", "stale", 9)
        assertEquals("newer", state.projections.getJSONObject("values").getString("title"))
    }
    @Test fun logicalStreamFailureEndsItsOwner() {
        val router = RemoteStreamRouter { }
        var failure: java.io.IOException? = null
        val id = router.open("\$events", JSONObject(), ended = { failure = it }) { }
        router.accept(JSONObject().put("type", "error").put("streamId", id).put("error", JSONObject().put("code", "test/failure").put("message", "Failed")))
        assertEquals("test/failure", (failure as HarnessApi.RemoteFailure).code)
    }

    @Test fun approvalsExpireOnSettlementOrClientReplacement() {
        val events = HarnessRemoteEvents()
        events.ready("c1")
        events.offer("event1", "s1")
        assertTrue(events.valid("c1", "event1", "s1"))
        events.settle("event1")
        assertFalse(events.valid("c1", "event1", "s1"))
        events.offer("event2", "s1")
        events.ready("c2")
        assertFalse(events.valid("c1", "event2", "s1"))
    }

    @Test fun replayedEventIdInNewGenerationCannotBeUnlockedByOldFailure() {
        val events = HarnessRemoteEvents()
        val oldGeneration = events.ready("c1")
        events.offer("replayed", "s1", "question", oldGeneration)
        assertTrue(events.reserve("c1", "replayed", "s1", "question", oldGeneration))
        val newGeneration = events.ready("c2")
        events.offer("replayed", "s1", "question", newGeneration)
        assertTrue(events.reserve("c2", "replayed", "s1", "question", newGeneration))

        events.unreserve("replayed", oldGeneration)
        assertNull(events.settle("replayed", oldGeneration))
        assertFalse(events.valid("c2", "replayed", "s1", "question", newGeneration))
        assertNotNull(events.settle("replayed", newGeneration))
    }

    @Test fun questionFramesKeepRequestAndSessionIdentityAcrossConcurrentRequests() {
        val one = parsePendingQuestion(JSONObject("""{"type":"question/requested","sessionId":"s1","eventId":"e1","_rpcId":"c1","generation":4,"questions":[{"id":"q1","question":"Choose","options":[{"label":"A"},{"label":"B"}]}]}"""))!!
        val two = parsePendingQuestion(JSONObject("""{"type":"question/requested","sessionId":"s2","eventId":"e2","_rpcId":"c1","generation":4,"questions":[{"id":"q1","question":"Choose","options":[{"label":"A"},{"label":"B"}]}]}"""))!!
        assertEquals("s1:e1", one.key)
        assertEquals("s2:e2", two.key)
        assertEquals("e1", one.eventId)
        assertEquals("s2", two.sessionId)
    }

    @Test fun answerBatchMustNameEveryQuestionAndUseOnlyOfferedChoices() {
        val questions = parseUserQuestions(JSONArray("""[{"id":"q1","question":"One","options":[{"label":"A"},{"label":"B"}]},{"id":"q2","question":"Two","multiSelect":true,"options":[{"label":"X"},{"label":"Y"}]}]"""))!!
        validateQuestionAnswers(questions, listOf(
            UserQuestionAnswerItem("q1", listOf("A")),
            UserQuestionAnswerItem("q2", listOf("X", "Y")),
        ))
        assertThrows(IllegalArgumentException::class.java) {
            validateQuestionAnswers(questions, listOf(UserQuestionAnswerItem("q1", listOf("A")), UserQuestionAnswerItem("other", emptyList())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateQuestionAnswers(questions, listOf(UserQuestionAnswerItem("q1", listOf("unoffered")), UserQuestionAnswerItem("q2", emptyList())))
        }
    }

    @Test fun freeTextQuestionUsesCustomAndEmptySelection() {
        val questions = parseUserQuestions(JSONArray("""[{"id":"q1","question":"What should I call it?"}]"""))!!
        validateQuestionAnswers(questions, listOf(UserQuestionAnswerItem("q1", emptyList(), "Harbor")))
        assertEquals("Harbor", questionAnswersJson(listOf(UserQuestionAnswerItem("q1", emptyList(), "Harbor")))
            .getJSONArray("answers").getJSONObject(0).getString("custom"))
    }

    @Test fun continuedQuestionSurvivesBaselineBeforeEventsReadyAndDropsQueuedReply() {
        val projection = JSONObject("""{"active":[{"callId":"call-a","state":"continued","questions":[{"id":"q1","question":"Choose"}]},{"callId":"call-b","state":"continued","questions":[{"id":"q2","question":"Name"}]}]}""")
        val history = parseContinuedQuestions(projection, "s1", null)
        assertEquals(2, history.size)
        assertTrue(history.all { it.generation == 0L })
        val inbox = JSONObject("""{"next-step":[{"source":{"kind":"user-question-reply","callId":"call-a"}}],"next-turn":[]}""")
        val restored = parseContinuedQuestions(projection, "s1", inbox)
        assertEquals(listOf("call-b"), restored.map { it.callId })
        assertEquals(0L, restored.single().generation)
    }

    @Test fun systemPromptIsRecognizedContext() {
        val entries = JSONArray("""[{"event":{"seq":0,"time":1,"type":"system/message","surfaceOp":"append","data":{"message":{"content":"Synthetic system instructions"}}}}]""")
        assertEquals(ChatMessage.ActivityKind.CONTEXT, ChatProjection.fromHistory(entries).single().activityKind)
    }

    @Test fun harnessAndAccessChallengesAreDistinct() {
        assertFalse(isAccessChallenge(401, null, null))
        assertTrue(isAccessChallenge(302, "https://team.cloudflareaccess.com/login", null))
        assertTrue(isAccessChallenge(401, null, "Bearer resource_metadata=..."))
    }
    @Test fun replacementRetiresEarlierSurfaceRows() {
        val state = HarnessSessionState()
        state.accept(JSONObject("""{"type":"snapshot","cursor":2,"records":[{"type":"event","event":{"seq":1,"type":"user/message","data":{"source":{"kind":"user"},"content":[{"type":"text","text":"old"}]},"surfaceOp":"append"}},{"type":"event","event":{"seq":2,"type":"user/message","data":{"source":{"kind":"user"},"content":[{"type":"text","text":"replacement"}]},"surfaceOp":{"op":"replace","startSeq":1,"endSeq":1}}}],"projections":{"values":{}},"assistantStream":{"revision":0}}"""))
        assertEquals(listOf("replacement"), ChatProjection.fromHistory(state.events()).map { it.text })
    }

    @Test fun endpointArgumentsAreExplicit() {
        assertTrue(rc2Arguments("session/list", JSONObject()).has("_request"))
        assertTrue(rc2Arguments("session/prompt", JSONObject().put("requestId", "r")).getJSONObject("request").has("requestId"))
        assertEquals("s", rc2Arguments("agentPresets/select", JSONObject().put("sessionId", "s").put("agentPreset", "default")).getString("agentId"))
    }

    @Test fun compactChunksUseFirstTimestampAndFollowingDeltas() {
        val chunks = expandCompactStream(JSONArray("""[{"type":"text-chunks","time0":100,"index":0,"dt":[5,9],"texts":["a","b","c"]}]"""))
        assertEquals(listOf(100L,105L,114L), chunks.map { it.getLong("time") })
    }
    @Test fun authenticationRedirectMustRemainAtOrigin() {
        assertEquals("http://127.0.0.1:3080/", HarnessAuthentication.cleanRedirect("http://127.0.0.1:3080", "./"))
        assertThrows(java.io.IOException::class.java) { HarnessAuthentication.cleanRedirect("https://example.com", "https://other.example/") }
    }

    @Test fun launchLinkSeparatesTokenFromSavedAddress() {
        val c = ServerConnection.parse("http://192.168.1.50:3080/?token=synthetic")
        assertEquals("http://192.168.1.50:3080", c.origin)
        assertEquals("synthetic", c.launchToken)
        assertFalse(c.toString().contains("synthetic"))
    }
    @Test fun malformedLaunchLinksAreRejected() {
        for (url in listOf("https://example.com/?token=a&token=b", "https://example.com/?token=", "https://example.com/?token=a&other=b", "https://example.com/path?token=a", "http://example.com/?token=a", "https://example.com/#token=a")) {
            assertThrows(IllegalArgumentException::class.java) { ServerConnection.parse(url) }
        }
    }
    @Test fun remoteRequestUsesNamedArguments() {
        val body = HarnessRpc.request("id-1", "session/list", JSONObject().put("_request", JSONObject()))
        assertEquals("client-request", body.getString("type"))
        assertEquals("session/list", body.getString("method"))
        assertTrue(body.getJSONObject("payload").getJSONObject("args").has("_request"))
    }
    @Test fun responseCorrelationAndTypeAreRequired() {
        val response = JSONObject("""{"type":"server-response","rpcId":"id-1","result":{"ok":true,"value":["one"]}}""")
        assertEquals("one", (HarnessRpc.value("id-1", response) as JSONArray).getString(0))
        assertThrows(java.io.IOException::class.java) { HarnessRpc.value("other", response) }
        response.put("type", "client-request")
        assertThrows(java.io.IOException::class.java) { HarnessRpc.value("id-1", response) }
    }
    @Test fun businessErrorsPreserveCode() {
        val response = JSONObject("""{"type":"server-response","rpcId":"id-1","result":{"ok":false,"error":{"code":"gateway/method-unavailable","message":"Not supported","details":{}}}}""")
        val error = assertThrows(HarnessApi.RemoteFailure::class.java) { HarnessRpc.value("id-1", response) }
        assertEquals("gateway/method-unavailable", error.code)
    }
    @Test fun logicalStreamsRouteAndCancelIndependently() {
        val sent = mutableListOf<JSONObject>()
        val items = mutableListOf<String>()
        val mux = RemoteStreamRouter { sent += it }
        val first = mux.open("session/control", JSONObject()) { items += it.getString("type") }
        val second = mux.open("session/follow", JSONObject().put("request", JSONObject())) { items += "second" }
        assertEquals("open", sent[0].getString("type"))
        mux.accept(JSONObject().put("type", "item").put("streamId", first).put("value", JSONObject().put("type", "baseline")))
        mux.cancel(first)
        mux.accept(JSONObject().put("type", "item").put("streamId", first).put("value", JSONObject().put("type", "stale")))
        mux.accept(JSONObject().put("type", "item").put("streamId", second).put("value", JSONObject()))
        assertEquals(listOf("baseline", "second"), items)
        assertEquals("cancel", sent[2].getString("type"))
    }
    @Test fun journalSnapshotAndGapRepair() {
        val state = HarnessSessionState()
        state.accept(JSONObject("""{"type":"snapshot","cursor":4,"records":[{"type":"event","event":{"seq":4,"type":"turn/start","time":1,"data":{}}}],"projections":{"asOfSeq":4,"values":{}},"assistantStream":{"revision":0},"hasMore":false}"""))
        state.accept(JSONObject("""{"type":"event","event":{"seq":5,"type":"turn/end","time":2,"data":{}}}"""))
        state.accept(JSONObject("""{"type":"event","event":{"seq":5,"type":"turn/end","time":2,"data":{}}}"""))
        assertEquals(2, state.events().length())
        assertThrows(java.io.IOException::class.java) {
            state.accept(JSONObject("""{"type":"event","event":{"seq":7,"type":"turn/end","time":2,"data":{}}}"""))
        }
    }
    @Test fun assistantRevisionGapRejectsPartialOutput() {
        val state = HarnessSessionState()
        state.accept(JSONObject("""{"type":"snapshot","cursor":-1,"records":[],"projections":{"asOfSeq":-1,"values":{}},"assistantStream":{"revision":0},"hasMore":false}"""))
        assertThrows(java.io.IOException::class.java) {
            state.accept(JSONObject("""{"type":"assistant-stream","frame":{"type":"start","attemptId":"a","revision":2,"startedAfterSeq":-1,"turn":1,"step":1}}"""))
        }
    }
}
