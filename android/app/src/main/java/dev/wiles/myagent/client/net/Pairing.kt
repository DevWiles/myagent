package dev.wiles.myagent.client.net

/**
 * 扫到/填进来的那串东西是什么（协议 1.1 / 1.3）。
 *
 * <p>两种配对**都是一个 URL 本身**，不发明新 scheme——区别只在有没有 fragment：
 * 带 `#` 的是中继（`#` 后面是配对密钥），不带的是局域网直连（token 在查询串里）。
 * 判别写成纯函数，因为它是「扫码」和「手填」共用的那一条路，值得单独测。
 */
sealed interface Pairing {

    /** 局域网直连：`ws://192.168.x.x:端口/?token=…`。出了这个网段就废。 */
    data class Lan(val url: String) : Pairing

    /**
     * 中继：`wss://relay/ws?room=…#<配对密钥>`。任何网络都能用。
     *
     * @param key 配对密钥。**只留在本机**，连 relay 的 URL 里都不能出现。
     */
    data class Relay(val relayUrl: String, val room: String, val key: String) : Pairing {
        /** 真正去连的地址——注意这里没有 key，那是重点。 */
        fun clientUrl(): String = "$relayUrl?room=$room&role=client"
    }

    companion object {

        /**
         * 解析一串扫到的/手填的文本。认不出返回 null，调用方负责说人话。
         *
         * <p>刻意**不**接受「只有房间号」「只有密钥」这类残片：配对信息缺一半时
         * 连上去只会得到一堆解不开的密文，而用户完全不知道问题出在哪。
         */
        fun parse(raw: String): Pairing? {
            val text = raw.trim()
            val hash = text.indexOf('#')
            if (hash >= 0) {
                val base = text.substring(0, hash)
                val key = text.substring(hash + 1)
                val room = queryParam(base, "room")
                val looksWs = base.startsWith("ws://") || base.startsWith("wss://")
                if (!looksWs || room.isNullOrEmpty() || key.isEmpty()) {
                    return null
                }
                val relayUrl = base.substringBefore('?')
                return Relay(relayUrl, room, key)
            }
            if ((text.startsWith("ws://") || text.startsWith("wss://")) &&
                !queryParam(text, "token").isNullOrEmpty()
            ) {
                return Lan(text)
            }
            return null
        }

        private fun queryParam(url: String, key: String): String? {
            val q = url.indexOf('?')
            if (q < 0) return null
            return url.substring(q + 1).split("&")
                .firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')
        }
    }
}
