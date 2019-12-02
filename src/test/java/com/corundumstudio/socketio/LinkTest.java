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
import com.alibaba.fastjson.JSONObject;
import com.corundumstudio.socketio.listener.DataListener;
import com.corundumstudio.socketio.listener.DisconnectListener;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author xiaobaicai
 * @Date 2019-10-18
 **/
public class LinkTest {

    private static ConcurrentHashMap<String,String> sessionMap = new ConcurrentHashMap<String,String>();

    private static SocketIOServer server;

    public static void main(String[] args){
        Configuration configuration = new Configuration();

        configuration.setPort(7700);
        configuration.setTransports(Transport.WEBSOCKET);
        server = new SocketIOServer(configuration);
        server.start();
        SocketIONamespace socketIONamespace = server.addNamespace("/websocket/test");
        socketIONamespace.addEventListener("watch_time", String.class,new DataListener<String>(){
            public void onData(SocketIOClient client, String data, AckRequest ackSender) throws Exception {

                UUID sessionId = client.getSessionId();
                WatchTime watchTime = JSONObject.parseObject(data, WatchTime.class);
                if (watchTime.getType() == 1) {
                    System.out.println("----------sessionId= " + sessionId.toString() + "------ startTime= " + new Date());
                }

                if (watchTime.getType() == 2) {
                    System.out.println("----------sessionId= " + sessionId.toString() + "------ endTime= " + new Date());
                }

                System.out.println(watchTime);
                //直接回复
                ackSender.sendAckData("aaa");
            }
        });

        socketIONamespace.addDisconnectListener(new DisconnectListener() {
            @Override
            public void onDisconnect(SocketIOClient client) {
                UUID sessionId = client.getSessionId();
                System.out.println("----------sessionId= "+sessionId.toString()+"------closeTime= " +new Date());
            }
        });


    }
}
