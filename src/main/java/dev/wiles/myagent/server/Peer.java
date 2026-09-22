package dev.wiles.myagent.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.WebSocket;

/**
 * 一个连着的界面。
 *
 * <p>存在的理由：界面**不再只有一种接法**了。终端直连本机 WebSocket，
 * 手机走中继（一条出站连接 + 一层加密封套）。两者对 {@link AgentServer} 来说
 * 应该长得一模一样——不然每加一种接法，「广播事件」「定向回一条 error」
 * 这两件事就要在服务器里各写一遍分支，而漏写一处的症状是
 * 「某个界面上某个功能就是不响应」，极难查。
 */
public interface Peer {

    /** 发一帧。已经断了的连接静默丢弃，不抛异常——发送方没做错什么。 */
    void send(String json);

    boolean isOpen();

    default void send(ObjectNode node) {
        send(node.toString());
    }

    /** 本机 WebSocket 连接。 */
    static Peer of(WebSocket conn) {
        return new Peer() {
            @Override
            public void send(String json) {
                if (conn.isOpen()) {
                    conn.send(json);
                }
            }

            @Override
            public boolean isOpen() {
                return conn.isOpen();
            }
        };
    }
}
