package dev.matthes.privatemeetingnotes.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.matthes.privatemeetingnotes.AppContainer
import dev.matthes.privatemeetingnotes.data.ActionItem
import dev.matthes.privatemeetingnotes.data.Meeting
import dev.matthes.privatemeetingnotes.data.ProcessingJob
import dev.matthes.privatemeetingnotes.data.Speaker
import dev.matthes.privatemeetingnotes.data.Stage
import dev.matthes.privatemeetingnotes.data.Summary
import dev.matthes.privatemeetingnotes.data.Utterance
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.export.MarkdownExport
import dev.matthes.privatemeetingnotes.export.TranscriptCopy
import dev.matthes.privatemeetingnotes.models.ModelId
import dev.matthes.privatemeetingnotes.playback.AudioPlayer
import dev.matthes.privatemeetingnotes.processing.LiveProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TidyState {
    data object Idle : TidyState
    data class Working(val progress: Float) : TidyState
    data class Failed(val message: String) : TidyState
}

class MeetingViewModel(private val c: AppContainer, val meetingId: String) : ViewModel() {
    private fun <T> state(flow: kotlinx.coroutines.flow.Flow<T>, initial: T) =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val meeting: StateFlow<Meeting?> = state(c.database.meetings().observe(meetingId), null)
    val job: StateFlow<ProcessingJob?> = state(c.database.jobs().observe(meetingId), null)
    val live: StateFlow<LiveProgress?> = state(c.queue.progress.map { it?.takeIf { p -> p.meetingId == meetingId } }, null)
    val utterances: StateFlow<List<Utterance>> = state(c.database.transcripts().observeUtterances(meetingId), emptyList())
    val speakers: StateFlow<List<Speaker>> = state(c.database.transcripts().observeSpeakers(meetingId), emptyList())
    val summary: StateFlow<Summary?> = state(c.database.summaries().observe(meetingId), null)
    val actions: StateFlow<List<ActionItem>> = state(c.database.summaries().observeActions(meetingId), emptyList())
    val tidy = MutableStateFlow<TidyState>(TidyState.Idle)
    val repository get() = c.repository

    val llmAvailable: Boolean get() = c.llm.available()

    private var player: AudioPlayer? = null

    /** The recording's player, or null once its audio has been deleted. */
    fun player(): AudioPlayer? {
        player?.let { return it }
        val file = c.files.recording(meetingId)
        if (!file.exists()) return null
        return runCatching { AudioPlayer(file, viewModelScope) }.onFailure { Log.w("player could not open the recording", it) }.getOrNull()
            .also { player = it }
    }

    fun rename(title: String) = viewModelScope.launch { if (title.isNotBlank()) c.database.meetings().setTitle(meetingId, title.trim()) }

    /** Names a speaker and, unless voices aren't remembered, teaches the voiceprint of that name their voice. */
    fun renameSpeaker(label: Int, name: String, isSelf: Boolean) = viewModelScope.launch {
        val speaker = speakers.value.firstOrNull { it.label == label } ?: return@launch
        val voiceprintId = c.voiceprints.learn(speaker, name, isSelf)
        c.repository.renameSpeaker(meetingId, label, name, voiceprintId)
        c.indexer.scheduleMeeting(meetingId)
    }

    /** Whether [speaker]'s voiceprint is marked as the phone owner's. */
    suspend fun isSelf(speaker: Speaker): Boolean = speaker.voiceprintId?.let { c.database.voiceprints().get(it)?.isSelf } ?: false

    fun moveUtterance(utteranceId: Long, label: Int) = viewModelScope.launch {
        c.repository.moveUtterance(utteranceId, label)
        c.indexer.scheduleMeeting(meetingId)
    }

    fun askModel(): AskViewModel = AskViewModel(c, meetingId)

    fun setActionDone(id: Long, done: Boolean) = viewModelScope.launch { c.database.summaries().setDone(id, done) }

    /** Splits the speakers again for a new head count, reusing the transcription. */
    fun resplit(headCount: Int?) = viewModelScope.launch {
        c.database.meetings().setHeadCount(meetingId, headCount)
        c.queue.enqueue(meetingId, c.pipeline.resplitStage(meetingId, headCount))
    }

    fun reprocess() = viewModelScope.launch { c.queue.enqueue(meetingId, Stage.VAD) }

    fun retry() = viewModelScope.launch { c.queue.retry(meetingId) }

    fun regenerateSummary() = viewModelScope.launch { c.queue.enqueue(meetingId, Stage.SUMMARY) }

    fun cancel() = viewModelScope.launch { c.queue.cancel(meetingId) }

    fun writeTidyNotes() {
        if (tidy.value is TidyState.Working) return
        tidy.value = TidyState.Working(0f)
        c.scope.launch {
            try {
                val lines = c.pipeline.transcriptLines(meetingId)
                val terms = dev.matthes.privatemeetingnotes.asr.Vocabulary.terms(c.settings.current.vocabulary)
                val notes = c.summarizer.tidyNotes(lines, terms) { tidy.value = TidyState.Working(it) }
                c.database.summaries().setTidyNotes(meetingId, notes)
                tidy.value = TidyState.Idle
            } catch (e: Exception) {
                Log.e("tidy notes failed", e)
                tidy.value = TidyState.Failed(e.message ?: "Tidy notes could not be written")
            }
        }
    }

    fun deleteAudio() = viewModelScope.launch {
        player?.release()
        player = null
        c.repository.deleteAudio(meetingId)
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        player?.release()
        player = null
        c.queue.cancel(meetingId)
        c.repository.delete(meetingId)
        c.retriever.invalidate()
        onDone()
    }

    fun share(context: Context, includeTranscript: Boolean) {
        val meeting = meeting.value ?: return
        val markdown = MarkdownExport.meeting(meeting, summary.value, actions.value, speakers.value, utterances.value, c.repository, includeTranscript)
        MarkdownExport.share(context, meeting.title, markdown)
    }

    /** The characters copied, or null if the clipboard refused the transcript. */
    fun copyTranscript(context: Context): Int? {
        val meeting = meeting.value ?: return null
        val text = TranscriptCopy.text(speakers.value, utterances.value)
        return text.length.takeIf { TranscriptCopy.copy(context, meeting.title, text) }
    }

    override fun onCleared() {
        player?.release()
    }
}
