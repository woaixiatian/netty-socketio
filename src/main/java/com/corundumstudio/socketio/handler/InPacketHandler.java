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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.CharsetUtil;

import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corundumstudio.socketio.listener.ExceptionListener;
import com.corundumstudio.socketio.messages.PacketsMessage;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.namespace.NamespacesHub;
import com.corundumstudio.socketio.protocol.Packet;
import com.corundumstudio.socketio.protocol.PacketDecoder;
import com.corundumstudio.socketio.protocol.PacketType;
import com.corundumstudio.socketio.transport.NamespaceClient;

@Sharable
public class InPacketHandler extends SimpleChannelInboundHandler<PacketsMessage> {

    private static final Logger log = LoggerFactory.getLogger(InPacketHandler.class);

    private final PacketListener packetListener;
    private final PacketDecoder decoder;
    private final NamespacesHub namespacesHub;
    private final ExceptionListener exceptionListener;

    public InPacketHandler(PacketListener packetListener, PacketDecoder decoder, NamespacesHub namespacesHub, ExceptionListener exceptionListener) {
        super();
        this.packetListener = packetListener;
        this.decoder = decoder;
        this.namespacesHub = namespacesHub;
        this.exceptionListener = exceptionListener;
    }

    public static int count= 0 ;

    @Override
    protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, PacketsMessage message)
                throws Exception {
        System.out.println("InPacketHandler---channelRead0");
        System.out.println("---InPacketHandler--remoteAddress--"+ctx.channel().remoteAddress());
        ByteBuf content = message.getContent();
        ClientHead client = message.getClient();

        if (log.isTraceEnabled()) {
            log.trace("In message: {} sessionId: {}", content.toString(CharsetUtil.UTF_8), client.getSessionId());
        }
        log.error("--------------------count-----------------"+count);
        while (content.isReadable()) {
            try {
                //解析请求包 解析出当亲请求的类型，所属namespace标识 消息体等等
                // 这块比较暂时未分析
                Packet packet = decoder.decodePackets(content, client);
                if (packet.hasAttachments() && !packet.isAttachmentsLoaded()) {
                    return;
                }
                //根据namespace标识，获取详情
                //namespace一般是通过直接调取SocketIOServer
                Namespace ns = namespacesHub.get(packet.getNsp());
                if (ns == null) {
                    //如果不存在，并且是连接请求，那么直接返回错误信息
                    if (packet.getSubType() == PacketType.CONNECT) {
                        Packet p = new Packet(PacketType.MESSAGE);
                        p.setSubType(PacketType.ERROR);
                        p.setNsp(packet.getNsp());
                        p.setData("Invalid namespace");
                        client.send(p);
                        return;
                    }
                    log.debug("Can't find namespace for endpoint: {}, sessionId: {} probably it was removed.", packet.getNsp(), client.getSessionId());
                    return;
                }
                //建立链接的请求，那么把该namespace与NamespaceClient做映射关系
                //NamespaceClient 包含了ClientHead和namespace信息
                if (packet.getSubType() == PacketType.CONNECT) {
                    client.addNamespaceClient(ns);
                }
                //获取该 namespace 映射的NamespaceClient
                NamespaceClient nClient = client.getChildClient(ns);
                if (nClient == null) {
                    log.debug("Can't find namespace client in namespace: {}, sessionId: {} probably it was disconnected.", ns.getName(), client.getSessionId());
                    return;
                }
                //处理相关的请求
                packetListener.onPacket(packet, nClient, message.getTransport());
            } catch (Exception ex) {
                String c = content.toString(CharsetUtil.UTF_8);
                log.error("Error during data processing. Client sessionId: " + client.getSessionId() + ", data: " + c, ex);
                throw ex;
            }
        }
    }
    //
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        System.out.println("InPacketHandler---channelRead");
        boolean release = true;
        try {
            if (acceptInboundMessage(msg)) {
                @SuppressWarnings("unchecked")
                PacketsMessage imsg = (PacketsMessage) msg;
                channelRead0(ctx, imsg);
            } else {
                release = false;
                ctx.fireChannelRead(msg);
            }
        } finally {
            if (release) {
                ReferenceCountUtil.release(msg);
            }
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        if (!exceptionListener.exceptionCaught(ctx, e)) {
            super.exceptionCaught(ctx, e);
        }
    }

}
