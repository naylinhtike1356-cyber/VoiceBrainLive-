package com.example.voicebrainlive.agent.notion

/** Closed generated sample rows only. Never accesses an account or reads the supplied secret. */
object SyntheticNotionDemoPort : SyntheticNotionPort {
    override suspend fun query(request: SyntheticPageRequest, secretLease: CharArray): SyntheticPageReply {
        val pin = freezeReadPin(request.pin)
        if (pin.evidence != EvidenceSource.SYNTHETIC_FIXTURE || request.cursor != null || request.pageSize < 2)
            return SyntheticPageReply.Error(ProfileFailure.SCHEMA_MISMATCH)
        val unfinished = pin.schema.unfinishedValues.sorted().first()
        val finished = (0..10).map { "SyntheticFinished$it" }.first { it !in pin.schema.unfinishedValues }
        val rows = profileList(listOf(
            SyntheticTaskRow("synthetic_open", pin.resource.dataSourceId,
                "SYNTHETIC study sample — no Notion account data", unfinished, "Synced", 80),
            SyntheticTaskRow("synthetic_finished", pin.resource.dataSourceId,
                "SYNTHETIC finished sample — no Notion account data", finished, "Pending", 100)))
        return SyntheticPageReply.Page(pin.resource, pin.schema.fingerprint, rows, false, null,
            QueryCompletion.COMPLETE, responseBytes = 1_024)
    }
}
