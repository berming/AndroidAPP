package com.communicationcard.game.ui.multiplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.communicationcard.game.R
import com.communicationcard.game.network.RoomInfo
import com.communicationcard.game.network.RoomStatus

class RoomListAdapter(
    private val onRoomClick: (RoomInfo) -> Unit
) : ListAdapter<RoomInfo, RoomListAdapter.RoomViewHolder>(RoomDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_list, parent, false)
        return RoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        holder.bind(getItem(position), onRoomClick)
    }

    class RoomViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRoomName: TextView = itemView.findViewById(R.id.tvRoomName)
        private val tvRoomCode: TextView = itemView.findViewById(R.id.tvRoomCode)
        private val tvPlayerCount: TextView = itemView.findViewById(R.id.tvPlayerCount)
        private val tvRoomStatus: TextView = itemView.findViewById(R.id.tvRoomStatus)

        fun bind(room: RoomInfo, onClick: (RoomInfo) -> Unit) {
            tvRoomName.text = room.roomName.ifEmpty { "房间 ${room.roomCode}" }
            tvRoomCode.text = "房间码: ${room.roomCode}"
            tvPlayerCount.text = "${room.players.size}/${room.maxPlayers}"
            tvRoomStatus.text = when (room.status) {
                RoomStatus.WAITING -> "等待中"
                RoomStatus.STARTING -> "即将开始"
                RoomStatus.IN_GAME -> "游戏中"
                RoomStatus.FINISHED -> "已结束"
            }

            itemView.alpha = if (room.status == RoomStatus.WAITING) 1f else 0.6f
            itemView.isClickable = room.status == RoomStatus.WAITING

            if (room.status == RoomStatus.WAITING) {
                itemView.setOnClickListener { onClick(room) }
            } else {
                itemView.setOnClickListener(null)
            }
        }
    }

    class RoomDiffCallback : DiffUtil.ItemCallback<RoomInfo>() {
        override fun areItemsTheSame(oldItem: RoomInfo, newItem: RoomInfo): Boolean {
            return oldItem.roomId == newItem.roomId
        }

        override fun areContentsTheSame(oldItem: RoomInfo, newItem: RoomInfo): Boolean {
            return oldItem == newItem
        }
    }
}
