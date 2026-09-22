package dev.wiles.myagent.client

import android.app.Application
import dev.wiles.myagent.client.net.AgentClient

/**
 * 连接的宿主。AgentClient 挂在 Application 上而不是 Activity/ViewModel：
 * 转屏重建 Activity、切后台回收界面，都不该把和后端的连接一起埋掉。
 * 不引 DI 框架——学习项目，依赖从哪来要一眼看得见。
 */
class AgentApp : Application() {
    val client: AgentClient by lazy { AgentClient() }
}
