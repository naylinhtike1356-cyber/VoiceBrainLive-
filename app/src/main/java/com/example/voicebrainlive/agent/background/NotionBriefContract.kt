package com.example.voicebrainlive.agent.background

/** Scope is a user-authored one-shot read/data policy, never a biometric or side-effect grant. */
internal data class NotionBriefScope(val databaseId:String,val model:String,val binding:String,val automatic:Boolean=false) {
    override fun toString()="NotionBriefScope(content=<redacted>)"
}
internal data class NotionBriefResult(val summary:String,val sourceIds:List<String>,val matchedTasks:Int,val providerCalled:Boolean) {
    override fun toString()="NotionBriefResult(content=<redacted>)"
}
internal enum class BriefError { SCOPE_CHANGED, AUTHENTICATION, RATE_LIMIT, INCOMPLETE_SOURCE, NETWORK, INVALID_RESPONSE, PERMISSION, INTERRUPTED }
internal class NotionBriefFailure(val failure:BriefError):Exception(failure.name)
internal fun validateBrief(scope:NotionBriefScope){
    requestCheck(scope.databaseId.matches(Regex("[a-f0-9]{32}"))&&scope.binding.matches(Regex("[a-f0-9]{64}")))
    requestCheck(scope.model.matches(Regex("[A-Za-z0-9._-]{1,80}"))&&!scope.model.contains("live",true)&&!scope.model.contains("audio",true))
}
internal fun validateBriefResult(result:NotionBriefResult){
    requestCheck(result.summary.isNotBlank()&&result.summary.length<=3000&&result.sourceIds.size<=10&&result.sourceIds.distinct().size==result.sourceIds.size)
    requestCheck(result.sourceIds.all{it.matches(Regex("[a-f0-9]{32}"))}&&result.matchedTasks in result.sourceIds.size..75)
    requestCheck(result.providerCalled||result.matchedTasks==0)
}
internal fun interface NotionBriefPort {
    /** reserveGemini must commit the global 24h budget BEFORE sending the one paid request. */
    suspend fun generate(scope:NotionBriefScope,current:()->Boolean,reserveGemini:()->Unit):NotionBriefResult
}
