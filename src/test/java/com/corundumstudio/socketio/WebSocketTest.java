package com.corundumstudio.socketio;

import com.alibaba.fastjson.JSONObject;
import com.corundumstudio.socketio.handler.ClientHead;
import com.corundumstudio.socketio.protocol.Packet;
import com.corundumstudio.socketio.protocol.PacketType;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import com.corundumstudio.socketio.transport.NamespaceClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

/**
 * @Author xiaobaicai
 * @Date 2019-11-27
 **/
public class WebSocketTest {

    private static SocketIOServer server;

    public static void main(String[] args) {

        Configuration configuration = new Configuration();

        configuration.setPort(7700);
        configuration.setTransports(Transport.WEBSOCKET);
        configuration.setPingInterval(120000);
        configuration.setPingTimeout(300000);
        configuration.setStoreFactory(new RedissonStoreFactory());
        server = new SocketIOServer(configuration);
        server.start();
        SocketIONamespace socketIONamespace = server.addNamespace("/websocket/test");

        BroadcastOperations broadcastOperations = socketIONamespace.getBroadcastOperations();

        broadcastOperations.send(new Packet(PacketType.MESSAGE));

        socketIONamespace.addEventListener("watch_finish", String.class, (client, data, ackRequest) -> {

            UUID sessionId = client.getSessionId();


        });


        socketIONamespace.addDisconnectListener(client -> {
            UUID sessionId = client.getSessionId();
            System.out.println("----------sessionId= " + sessionId.toString() + "------ end= ");

        });
        // 停止观看
        socketIONamespace.addConnectListener(client -> {
            NamespaceClient client1 = (NamespaceClient) client;
            ClientHead baseClient = client1.getBaseClient();
            HandshakeData handshakeData = baseClient.getHandshakeData();
            InetSocketAddress local = handshakeData.getLocal();
            InetAddress address = local.getAddress();

//            JFinalJson json = new JFinalJson();
//            Packet packet = new Packet(PacketType.MESSAGE);
//            packet.setSubType(PacketType.EVENT);
//            packet.setName("test");
//            WebsocketMessage message = new WebsocketMessage();
//            message.setData(address.getHostAddress());
//            json.setDatePattern("yyyy-MM-dd HH:mm:ss");
//            packet.setData(Arrays.asList(json.toJson(message)));
//            client.send(packet);
            System.out.println(address.getHostAddress());
        });
    }
}
