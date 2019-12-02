/**
 * Copyright 2012 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.corundumstudio.socketio.handler;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.Disconnectable;
import com.corundumstudio.socketio.DisconnectableHub;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.Transport;
import com.corundumstudio.socketio.ack.AckManager;
import com.corundumstudio.socketio.messages.HttpErrorMessage;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.namespace.NamespacesHub;
import com.corundumstudio.socketio.protocol.AuthPacket;
import com.corundumstudio.socketio.protocol.Packet;
import com.corundumstudio.socketio.protocol.PacketType;
import com.corundumstudio.socketio.scheduler.CancelableScheduler;
import com.corundumstudio.socketio.scheduler.SchedulerKey;
import com.corundumstudio.socketio.scheduler.SchedulerKey.Type;
import com.corundumstudio.socketio.store.StoreFactory;
import com.corundumstudio.socketio.store.pubsub.ConnectMessage;
import com.corundumstudio.socketio.store.pubsub.PubSubType;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

/**
 * 注册的处理器
 * 对于加入聊天室的请求的处理
 *
 * */
@Sharable
public class AuthorizeHandler extends ChannelInboundHandlerAdapter implements Disconnectable {

    private static final Logger log = LoggerFactory.getLogger(AuthorizeHandler.class);

    //
    private final CancelableScheduler disconnectScheduler;

    private final String connectPath;
    private final Configuration configuration;
    private final NamespacesHub namespacesHub;
    private final StoreFactory storeFactory;

    private final DisconnectableHub disconnectable;
    private final AckManager ackManager;
    private final ClientsBox clientsBox;

    public AuthorizeHandler(String connectPath, CancelableScheduler scheduler, Configuration configuration, NamespacesHub namespacesHub, StoreFactory storeFactory,
            DisconnectableHub disconnectable, AckManager ackManager, ClientsBox clientsBox) {
        super();
        this.connectPath = connectPath;
        this.configuration = configuration;
        this.disconnectScheduler = scheduler;
        this.namespacesHub = namespacesHub;
        this.storeFactory = storeFactory;
        this.disconnectable = disconnectable;
        this.ackManager = ackManager;
        this.clientsBox = clientsBox;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        SchedulerKey key = new SchedulerKey(Type.PING_TIMEOUT, ctx.channel());

        disconnectScheduler.schedule(key, new Runnable() {
            @Override
            public void run() {
                ctx.channel().close();
                log.debug("Client with ip {} opened channel but doesn't send any data! Channel closed!", ctx.channel().remoteAddress());
            }
        }, configuration.getFirstDataTimeout(), TimeUnit.MILLISECONDS);
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        System.out.println("---AuthorizeHandler--remoteAddress--"+ctx.channel().remoteAddress());
        //
        SchedulerKey key = new SchedulerKey(Type.PING_TIMEOUT, ctx.channel());
        disconnectScheduler.cancel(key);

        //判断是否为接入的请求，即HTTP请求
        if (msg instanceof FullHttpRequest) {

            System.out.println("---AuthorizeHandler------1");
            FullHttpRequest req = (FullHttpRequest) msg;
            //获取NIOSocketChannel 即一个socket通道
            Channel channel = ctx.channel();
            //获取请求参数   /socket.io/?EIO=3&transport=websocket
            QueryStringDecoder queryDecoder = new QueryStringDecoder(req.uri());


            if (!configuration.isAllowCustomRequests()

                    && !queryDecoder.path().startsWith(connectPath)) {
                HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
                channel.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
                req.release();

                System.out.println("---AuthorizeHandler--remoteAddress-- end "+ctx.channel().remoteAddress());
                return;
            }
            //获取唯一识别会话的唯一识别号，连接上可以携带浏览器保存在本地的会话标识
            //这里我们并没有携带在认证的url上
            List<String> sid = queryDecoder.parameters().get("sid");
            // connectPath 默认为 /socket.io/
            if (queryDecoder.path().equals(connectPath)
                    && sid == null) {
                //本地 页面原始 域名http://localhost:8081
                String origin = req.headers().get(HttpHeaderNames.ORIGIN);
                //进行聊天室成员认证
                if (!authorize(ctx, channel, origin, queryDecoder.parameters(), req)) {
                    System.out.println("---AuthorizeHandler--remoteAddress-- end "+ctx.channel().remoteAddress());
                    req.release();
                    return;
                }
                // forward message to polling or websocket handler to bind channel
            }
        }
        System.out.println("---AuthorizeHandler------2");
        ctx.fireChannelRead(msg);
    }

    private boolean authorize(ChannelHandlerContext ctx, Channel channel, String origin, Map<String, List<String>> params, FullHttpRequest req)
            throws IOException {
        //获取请求头
        Map<String, List<String>> headers = new HashMap<String, List<String>>(req.headers().names().size());
        for (String name : req.headers().names()) {
            List<String> values = req.headers().getAll(name);
            headers.put(name, values);
        }
        //封装整个握手数据
        HandshakeData data = new HandshakeData(req.headers(), params,
                                                (InetSocketAddress)channel.remoteAddress(),
                                                (InetSocketAddress)channel.localAddress(),
                                                    req.uri(), origin != null && !origin.equalsIgnoreCase("null"));

        boolean result = false;
        try {
            //默认为true
            result = configuration.getAuthorizationListener().isAuthorized(data);
        } catch (Exception e) {
            log.error("Authorization error", e);
        }
        //默认不走
        if (!result) {
            HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
            channel.writeAndFlush(res)
                    .addListener(ChannelFutureListener.CLOSE);
            log.debug("Handshake unauthorized, query params: {} headers: {}", params, headers);
            return false;
        }
        //根据请求头，生成UUID会话标识
        UUID sessionId = this.generateOrGetSessionIdFromRequest(req.headers());

        List<String> transportValue = params.get("transport");
        if (transportValue == null) {
            log.error("Got no transports for request {}", req.uri());

            HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
            channel.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
            return false;
        }
        //判断传输协议，这里是websocket
        Transport transport = Transport.byName(transportValue.get(0));
        if (!configuration.getTransports().contains(transport)) {
            Map<String, Object> errorData = new HashMap<String, Object>();
            errorData.put("code", 0);
            errorData.put("message", "Transport unknown");

            channel.attr(EncoderHandler.ORIGIN).set(origin);
            channel.writeAndFlush(new HttpErrorMessage(errorData));
            return false;
        }
        //初始化会话的请求信息
        ClientHead client = new ClientHead(sessionId, ackManager, disconnectable, storeFactory, data, clientsBox, transport, disconnectScheduler, configuration);
        //socket通道
        channel.attr(ClientHead.CLIENT).set(client);
        //将会话信息保存，方便后续调取，这里是保存sessionId和ClientHead的映射关系
        clientsBox.addClient(client);

        String[] transports = {};
        if (configuration.getTransports().contains(Transport.WEBSOCKET)) {
            transports = new String[] {"websocket"};
        }

        //认证成功返回信息
        //pingInterval: 60000   心跳监测评率 单位毫秒，ms
        //pingTimeout: 60000    心跳监测超时时间 单位毫秒，ms
        //sid: "56cc357b-49c1-444b-ac80-4a9610e29650"  会话唯一标识，之后的交互都带上，用于标识客户端
        //upgrades: ["websocket"]  切换的长连接协议，这里为websocket(也可以是polling轮询的方式)
        AuthPacket authPacket = new AuthPacket(sessionId, transports, configuration.getPingInterval(), configuration.getPingTimeout());

        //这里该ClientHead并没有在websocket模式下 Transport绑定的channel，因此实际上发送不出去packet
        //该open实际上在WebSocketTransport中来实现的
        Packet packet = new Packet(PacketType.OPEN);
        packet.setData(authPacket);
        client.send(packet);
        //超时定时任务
        client.schedulePingTimeout();
        log.debug("Handshake authorized for sessionId: {}, query params: {} headers: {}", sessionId, params, headers);
        return true;
    }

    /**
        This method will either generate a new random sessionId or will retrieve the value stored
        in the "io" cookie.  Failures to parse will cause a logging warning to be generated and a
        random uuid to be generated instead (same as not passing a cookie in the first place).

     这里如果可以通过请求头传递生成UUID会话标识的数据，便于可以实现同一客户端再一次会话过程中，使用同一个UUID
     如果没有改标识，那么只能随机生成一个uuid来保证唯一性

     socket.io 在websocket模式下是不支持携带请求头的，所以采用websocket模式，目前不支持保留回话UUID

    */
    private UUID generateOrGetSessionIdFromRequest(HttpHeaders headers) {
        List<String> values = headers.getAll("io");
        if (values.size() == 1) {
            try {
                return UUID.fromString(values.get(0));
            } catch ( IllegalArgumentException iaex ) {
                log.warn("Malformed UUID received for session! io=" + values.get(0));
            }
        }
        
        for (String cookieHeader: headers.getAll(HttpHeaderNames.COOKIE)) {
            Set<Cookie> cookies = ServerCookieDecoder.LAX.decode(cookieHeader);

            for (Cookie cookie : cookies) {
                if (cookie.name().equals("io")) {
                    try {
                        return UUID.fromString(cookie.value());
                    } catch ( IllegalArgumentException iaex ) {
                        log.warn("Malformed UUID received for session! io=" + cookie.value());
                    }
                }
            }
        }
        
        return UUID.randomUUID();
    }

    public void connect(UUID sessionId) {
        SchedulerKey key = new SchedulerKey(Type.PING_TIMEOUT, sessionId);
        disconnectScheduler.cancel(key);
    }

    /**
     * 添加到默认namespace，所有的client都需要添加到
     * 默认namespace中
     * */
    public void connect(ClientHead client) {
        Namespace ns = namespacesHub.get(Namespace.DEFAULT_NAME);

        if (!client.getNamespaces().contains(ns)) {
            Packet packet = new Packet(PacketType.MESSAGE);
            packet.setSubType(PacketType.CONNECT);
            client.send(packet);


            configuration.getStoreFactory().pubSubStore().publish(PubSubType.CONNECT, new ConnectMessage(client.getSessionId()));

            SocketIOClient nsClient = client.addNamespaceClient(ns);
            ns.onConnect(nsClient);
        }
    }

    @Override
    public void onDisconnect(ClientHead client) {
        clientsBox.removeClient(client.getSessionId());
    }

}
