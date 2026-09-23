package dev.wiles.myagent.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.wiles.myagent.client.AgentApp
import dev.wiles.myagent.client.protocol.ClientCommand

/**
 * 通知上的「同意 / 拒绝」按钮。这个 app 存在的场景就是人不在电脑前——
 * 锁屏上直接点掉，比解锁、进 app、等界面起来再点快好几步。
 *
 * <p>「以后都同意」和「补一句话」要挑、要打字，不放通知里，点通知本体进 app 做。
 */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val client = (context.applicationContext as AgentApp).client
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        // 只答还挂着的那一个：通知可能落后一拍（别的屏已经答了、或者已经换了下一个），
        // 拿旧 id 去答会把后端搞糊涂
        if (client.store.state.value.approval?.id != id) return
        client.store.clearApproval()
        client.send(ClientCommand.approvalReply(id, decision))
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_DECISION = "decision"
    }
}
