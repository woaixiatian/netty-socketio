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
/*
 * @(#)WebSocketTransportTest.java 2018. 5. 23.
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
package com.corundumstudio.socketio.transport;



import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import org.junit.Test;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;


/**
 * @author hangsu.cho@navercorp.com
 *
 */
public class WebSocketTransportTest {

  /**
   * Test method for {@link com.corundumstudio.socketio.transport.WebSocketTransport#}.
   */
  @Test
  public void testCloseFrame() {

    Configuration configuration = new Configuration();
    configuration.setHostname("127.0.0.1");
    configuration.setPort(8089);

    configuration.setStoreFactory(new RedissonStoreFactory());
    SocketIOServer server = new SocketIOServer(configuration);
    server.start();
    try {
      Thread.sleep(Integer.MAX_VALUE);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    server.stop();
  }

  private EmbeddedChannel createChannel() {
    return new EmbeddedChannel(new WebSocketTransport(false, null, null, null, null) {
      /*
       * (non-Javadoc)
       * 
       * @see
       * com.corundumstudio.socketio.transport.WebSocketTransport#channelInactive(io.netty.channel.
       * ChannelHandlerContext)
       */
      @Override
      public void channelInactive(ChannelHandlerContext ctx) throws Exception {}
    });
  }

}
