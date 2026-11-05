package com.stanislavlyalin.stopenuresis.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.audio.AudioFragmentAnalyzer
import com.stanislavlyalin.stopenuresis.audio.AudioFragmentInfo
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class AudioFragmentsAdapter(
    private val onPlay: (File) -> Unit,
    private val onStop: () -> Unit,
    private val onDelete: (File) -> Unit,
    private val onReject: (File) -> Unit,
    private val onApprove: (File) -> Unit
) : RecyclerView.Adapter<AudioFragmentsAdapter.ViewHolder>() {

    private val files = mutableListOf<File>()
    private val audioInfoCache = mutableMapOf<String, CachedAudioInfo>()
    private val sourceDateFormat = SimpleDateFormat("yyyy.MM.dd_HH.mm.ss", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("HH:mm:ss dd.MM.yyyy", Locale.getDefault())
    private var playingFilePath: String? = null
    private var playbackProgress: Float = 0f

    fun submitFiles(newFiles: List<File>) {
        files.clear()
        files.addAll(newFiles)
        notifyDataSetChanged()
    }

    fun setPlayback(file: File?, progress: Float) {
        val previousPath = playingFilePath
        val nextPath = file?.absolutePath
        playingFilePath = nextPath
        playbackProgress = progress.coerceIn(0f, 1f)

        notifyFileChanged(previousPath)
        if (nextPath != previousPath) notifyFileChanged(nextPath)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_fragment_placeholder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position], position == 0 || formatDisplayName(files[position]).takeLast(10) != formatDisplayName(files[position - 1]).takeLast(10))
    }

    override fun getItemCount(): Int = files.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFileName: TextView = itemView.findViewById(R.id.tvAudioFragmentFileName)
        private val tvAverageAmplitude: TextView =
            itemView.findViewById(R.id.tvAudioFragmentAverageAmplitude)
        private val waveformView: WaveformView = itemView.findViewById(R.id.waveformAudioFragment)
        private val btnPlay: ImageButton = itemView.findViewById(R.id.btnPlay)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
        private val btnReject: android.widget.Button = itemView.findViewById(R.id.btnReject)
        private val btnAccept: android.widget.Button = itemView.findViewById(R.id.btnAccept)

        fun bind(file: File, showDate: Boolean) {
            val date = itemView.findViewById<TextView>(R.id.sampleDate)
            date.text = formatDisplayName(file).takeLast(10)
            date.visibility = if (showDate) View.VISIBLE else View.GONE
            val isPlaying = file.absolutePath == playingFilePath
            val audioInfo = getAudioInfo(file)

            tvFileName.text = formatDisplayName(file).substringBefore(" ")
            if (file.name.endsWith(CLASSIFIER_DETECTED_SUFFIX)) {
                tvFileName.setTextColor(ContextCompat.getColor(itemView.context, R.color.rustling))
                tvFileName.setTypeface(null, Typeface.BOLD)
            } else {
                tvFileName.setTextColor(ContextCompat.getColor(itemView.context, R.color.ui_text))
                tvFileName.setTypeface(null, Typeface.NORMAL)
            }
            tvAverageAmplitude.text = audioInfo.averageAmplitude.toString()
            waveformView.setWaveform(audioInfo.waveform)
            waveformView.setProgress(if (isPlaying) playbackProgress else 0f)

            btnPlay.setImageResource(
                if (isPlaying) R.drawable.ic_stop_24 else R.drawable.ic_nav_play
            )
            btnPlay.contentDescription = itemView.context.getString(
                if (isPlaying) R.string.stopAudioFragment else R.string.playAudioFragment
            )
            btnPlay.setOnClickListener {
                if (isPlaying) {
                    onStop()
                } else {
                    onPlay(file)
                }
            }
            btnDelete.setOnClickListener { anchor ->
                android.widget.PopupMenu(itemView.context, anchor).apply {
                    menu.add(R.string.deleteAudioFragment).setOnMenuItemClickListener { onDelete(file); true }
                    show()
                }
            }
            btnReject.setOnClickListener { onReject(file) }
            btnAccept.setOnClickListener { onApprove(file) }
        }
    }

    private fun getAudioInfo(file: File): AudioFragmentInfo {
        val cached = audioInfoCache[file.absolutePath]
        if (cached != null && cached.lastModified == file.lastModified()) {
            return cached.info
        }

        val info = runCatching { AudioFragmentAnalyzer.analyze(file) }
            .getOrDefault(AudioFragmentInfo(emptyList(), 0))
        audioInfoCache[file.absolutePath] = CachedAudioInfo(file.lastModified(), info)
        return info
    }

    private fun notifyFileChanged(path: String?) {
        if (path == null) return
        val index = files.indexOfFirst { it.absolutePath == path }
        if (index >= 0) notifyItemChanged(index)
    }

    private fun formatDisplayName(file: File): String {
        val timestamp = file.name.substringBeforeLast(".wav")
            .removeSuffix("_unchecked")
            .removeSuffix("_detected")
            .removeSuffix("_1")
            .removeSuffix("_0")

        return try {
            val date = sourceDateFormat.parse(timestamp)
            if (date != null) displayDateFormat.format(date) else timestamp
        } catch (_: ParseException) {
            timestamp
        }
    }

    private data class CachedAudioInfo(
        val lastModified: Long,
        val info: AudioFragmentInfo
    )

    private companion object {
        const val CLASSIFIER_DETECTED_SUFFIX = "_1_detected_unchecked.wav"
    }
}
