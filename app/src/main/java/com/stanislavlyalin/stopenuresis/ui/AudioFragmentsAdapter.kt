package com.stanislavlyalin.stopenuresis.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.stanislavlyalin.stopenuresis.R
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class AudioFragmentsAdapter(
    private val onPlay: (File) -> Unit,
    private val onRemove: (File) -> Unit,
    private val onApprove: (File) -> Unit
) : RecyclerView.Adapter<AudioFragmentsAdapter.ViewHolder>() {

    private val files = mutableListOf<File>()
    private val sourceDateFormat = SimpleDateFormat("yyyy.MM.dd_HH.mm.ss", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("HH:mm:ss dd.MM.yyyy", Locale.getDefault())

    fun submitFiles(newFiles: List<File>) {
        files.clear()
        files.addAll(newFiles)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_fragment_placeholder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount(): Int = files.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFileName: TextView = itemView.findViewById(R.id.tvAudioFragmentFileName)
        private val btnPlay: ImageButton = itemView.findViewById(R.id.btnPlay)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
        private val btnAccept: ImageButton = itemView.findViewById(R.id.btnAccept)

        fun bind(file: File) {
            tvFileName.text = formatDisplayName(file)
            btnPlay.setOnClickListener { onPlay(file) }
            btnDelete.setOnClickListener { onRemove(file) }
            btnAccept.setOnClickListener { onApprove(file) }
        }
    }

    private fun formatDisplayName(file: File): String {
        val timestamp = file.name.substringBeforeLast(".wav")
            .removeSuffix("_unchecked")
            .removeSuffix("_1")
            .removeSuffix("_0")

        return try {
            val date = sourceDateFormat.parse(timestamp)
            if (date != null) displayDateFormat.format(date) else timestamp
        } catch (_: ParseException) {
            timestamp
        }
    }
}
