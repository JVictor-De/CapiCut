package com.capicut.audiointel.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.capicut.audiointel.project.LocalProjectMeta
import com.tharunbirla.librecuts.R

class LocalProjectAdapter(
    private var items: List<LocalProjectMeta>,
    private val onOpen: (LocalProjectMeta) -> Unit,
    private val onRename: (LocalProjectMeta) -> Unit,
    private val onDelete: (LocalProjectMeta) -> Unit
) : RecyclerView.Adapter<LocalProjectAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val open: View = view.findViewById(R.id.btnOpenLocalProject)
        val name: TextView = view.findViewById(R.id.tvLocalProjectName)
        val meta: TextView = view.findViewById(R.id.tvLocalProjectMeta)
        val rename: ImageButton = view.findViewById(R.id.btnRenameLocalProject)
        val delete: ImageButton = view.findViewById(R.id.btnDeleteLocalProject)
    }

    fun submit(next: List<LocalProjectMeta>) {
        items = next
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_local_project, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.displayName
        val whenText = DateUtils.getRelativeTimeSpanString(item.lastModifiedAt).toString()
        holder.meta.text = if (item.missingMedia) {
            holder.itemView.context.getString(R.string.capicut_missing_media)
        } else {
            whenText
        }
        holder.open.setOnClickListener { onOpen(item) }
        holder.rename.setOnClickListener { onRename(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = items.size
}
