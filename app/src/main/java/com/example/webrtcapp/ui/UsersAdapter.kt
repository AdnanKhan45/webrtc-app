package com.example.webrtcapp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.webrtcapp.R

data class UiUser(
    val uid: String,
    val displayName: String,
    val deviceToken: String?
)

class UsersAdapter(
    private val onCall: (UiUser) -> Unit
) : RecyclerView.Adapter<UsersAdapter.VH>() {

    private val items = mutableListOf<UiUser>()

    fun submit(list: List<UiUser>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_user, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val u = items[position]
        holder.name.text = u.displayName.ifBlank { "(Unnamed)" }
        holder.sub.text = u.uid
        holder.call.isEnabled = !u.uid.isBlank() && !u.displayName.isBlank()
        holder.call.setOnClickListener { onCall(u) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val name: TextView   = v.findViewById(R.id.nameText)
        val sub: TextView    = v.findViewById(R.id.subText)
        val call: Button     = v.findViewById(R.id.btnCall)
    }
}