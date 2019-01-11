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
package com.corundumstudio.socketio;

import com.corundumstudio.socketio.listener.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.namespace.NamespacesHub;

/**
 * Fully thread-safe.
 *
 */
public class SocketIOServer implements ClientListeners {

    private static final Logger log = LoggerFactory.getLogger(SocketIOServer.class);

    private final Configuration configCopy;
    private final Configuration configuration;

    private final NamespacesHub namespacesHub;
    private final SocketIONamespace mainNamespace;

    private SocketIOChannelInitializer pipelineFactory = new SocketIOChannelInitializer();

    /**
     * 工作组和线程组
     * 1个是用来接受请求,将请求添加到监听队列
     * 1一个是用来轮询监听队列,如果有IO任务,就分配线程处理
     * 从而实现IO多路复用, 基于Netty的IO多路复用是NIO,非阻塞的同步IO
     *
     * */
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public SocketIOServer(Configuration configuration) {
        this.configuration = configuration;
        this.configCopy = new Configuration(configuration);
        //命名空间名称 与 Namespace 的映射关系
        //一个Namespace 相当于是一个聊天群/组,内部会话内容共享
        //NamespacesHub 相当于是多个Namespace的集合,以及他们与name映射关系
        namespacesHub = new NamespacesHub(configCopy);
        //这里会新建一个默认的Namespace / SocketIONamespace
        // 实际上, 这个默认群组目前看是没有什么作用的?是否可以取消呢?可以测试下
        mainNamespace = addNamespace(Namespace.DEFAULT_NAME);
    }

    public void setPipelineFactory(SocketIOChannelInitializer pipelineFactory) {
        this.pipelineFactory = pipelineFactory;
    }

    /**
     * Get all clients connected to default namespace
     *
     * @return clients collection
     */
    public Collection<SocketIOClient> getAllClients() {
        return namespacesHub.get(Namespace.DEFAULT_NAME).getAllClients();
    }

    /**
     * Get client by uuid from default namespace
     *
     * @param uuid - id of client
     * @return client
     */
    public SocketIOClient getClient(UUID uuid) {
        return namespacesHub.get(Namespace.DEFAULT_NAME).getClient(uuid);
    }

    /**
     * Get all namespaces
     *
     * @return namespaces collection
     */
    public Collection<SocketIONamespace> getAllNamespaces() {
        return namespacesHub.getAllNamespaces();
    }

    public BroadcastOperations getBroadcastOperations() {
        return new BroadcastOperations(getAllClients(), configCopy.getStoreFactory());
    }

    /**
     * Get broadcast operations for clients within
     * room by <code>room</code> name
     *
     * @param room - name of room
     * @return broadcast operations
     */
    public BroadcastOperations getRoomOperations(String room) {
        Iterable<SocketIOClient> clients = namespacesHub.getRoomClients(room);
        return new BroadcastOperations(clients, configCopy.getStoreFactory());
    }

    /**
     * Start server
     */
    public void start() {
        startAsync().syncUninterruptibly();
    }

    /**
     * SOcketIOSever 启动的重要配置
     * 
     * @return void
     */
    public Future<Void> startAsync() {
        log.info("Session store / pubsub factory used: {}", configCopy.getStoreFactory());
        //http://svip.iocoder.cn/Netty/EventLoop-1-Reactor-Model/
        // 创建 boss 线程组 用于服务端接受客户端的连接-------- 1个
        // 创建 worker 线程组 用于进行 SocketChannel 的数据读写----------通常为CPU个数
        initGroups();

        //创建处理请求的 的Handlder 链
        pipelineFactory.start(configCopy, namespacesHub);
        //一个服务端用来监听新进来的连接的 TCP 的 Channel 。对于每一个新进来的连接，都会创建一个对应的 SocketChannel
        //readMessage()  SocketChannel ch = SocketUtils.accept(this.javaChannel()); 从channel从读取请求信息
        //   一个新连接到达ServerSocketChannel时，会创建一个SocketChannel
        //详见 http://ifeve.com/socket-channel/
        Class<? extends ServerChannel> channelClass = NioServerSocketChannel.class;
        if (configCopy.isUseLinuxNativeEpoll()) {
            channelClass = EpollServerSocketChannel.class;
        }

        // 创建 ServerBootstrap 对象
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
            //设置要被实例化的为 NioServerSocketChannel 类
        .channel(channelClass)
                //设置连入服务端的 Client 的 SocketChannel 的处理器
        .childHandler(pipelineFactory);
        // 设置 NioServerSocketChannel 的可选项
        applyConnectionOptions(b);

        InetSocketAddress addr = new InetSocketAddress(configCopy.getPort());
        if (configCopy.getHostname() != null) {
            addr = new InetSocketAddress(configCopy.getHostname(), configCopy.getPort());
        }

        // Start the server.
        // 绑定端口 即启动服务端 , 异步
        //http://svip.iocoder.cn/Netty/bootstrap-1-server/
        return b.bind(addr).addListener(new FutureListener<Void>() {
            @Override
            public void operationComplete(Future<Void> future) throws Exception {
                if (future.isSuccess()) {
                    log.info("SocketIO server started at port: {}", configCopy.getPort());
                } else {
                    log.error("SocketIO server start failed at port: {}!", configCopy.getPort());
                }
            }
        });
    }

    protected void applyConnectionOptions(ServerBootstrap bootstrap) {
        SocketConfig config = configCopy.getSocketConfig();
        bootstrap.childOption(ChannelOption.TCP_NODELAY, config.isTcpNoDelay());
        if (config.getTcpSendBufferSize() != -1) {
            bootstrap.childOption(ChannelOption.SO_SNDBUF, config.getTcpSendBufferSize());
        }
        if (config.getTcpReceiveBufferSize() != -1) {
            bootstrap.childOption(ChannelOption.SO_RCVBUF, config.getTcpReceiveBufferSize());
            bootstrap.childOption(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(config.getTcpReceiveBufferSize()));
        }
        bootstrap.childOption(ChannelOption.SO_KEEPALIVE, config.isTcpKeepAlive());
        bootstrap.childOption(ChannelOption.SO_LINGER, config.getSoLinger());

        bootstrap.option(ChannelOption.SO_REUSEADDR, config.isReuseAddress());
        bootstrap.option(ChannelOption.SO_BACKLOG, config.getAcceptBackLog());
    }

    protected void initGroups() {
        if (configCopy.isUseLinuxNativeEpoll()) {
            bossGroup = new EpollEventLoopGroup(configCopy.getBossThreads());
            workerGroup = new EpollEventLoopGroup(configCopy.getWorkerThreads());
        } else {
            bossGroup = new NioEventLoopGroup(configCopy.getBossThreads());
            workerGroup = new NioEventLoopGroup(configCopy.getWorkerThreads());
        }
    }

    /**
     * Stop server
     */
    public void stop() {
        bossGroup.shutdownGracefully().syncUninterruptibly();
        workerGroup.shutdownGracefully().syncUninterruptibly();

        pipelineFactory.stop();
        log.info("SocketIO server stopped");
    }

    public SocketIONamespace addNamespace(String name) {
        return namespacesHub.create(name);
    }

    public SocketIONamespace getNamespace(String name) {
        return namespacesHub.get(name);
    }

    public void removeNamespace(String name) {
        namespacesHub.remove(name);
    }

    /**
     * Allows to get configuration provided
     * during server creation. Further changes on
     * this object not affect server.
     *
     * @return Configuration object
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void addMultiTypeEventListener(String eventName, MultiTypeEventListener listener, Class<?>... eventClass) {
        mainNamespace.addMultiTypeEventListener(eventName, listener, eventClass);
    }

    @Override
    public <T> void addEventListener(String eventName, Class<T> eventClass, DataListener<T> listener) {
        mainNamespace.addEventListener(eventName, eventClass, listener);
    }

    @Override
    public void removeAllListeners(String eventName) {
        mainNamespace.removeAllListeners(eventName);
    }

    @Override
    public void addDisconnectListener(DisconnectListener listener) {
        mainNamespace.addDisconnectListener(listener);
    }

    @Override
    public void addConnectListener(ConnectListener listener) {
        mainNamespace.addConnectListener(listener);
    }

    @Override
    public void addPingListener(PingListener listener) {
        mainNamespace.addPingListener(listener);
    }

    @Override
    public void addListeners(Object listeners) {
        mainNamespace.addListeners(listeners);
    }
    
    @Override
    public void addListeners(Object listeners, Class<?> listenersClass) {
        mainNamespace.addListeners(listeners, listenersClass);
    }


}
