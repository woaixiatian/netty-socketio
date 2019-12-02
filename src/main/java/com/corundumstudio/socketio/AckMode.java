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

/**
 * 接收到消息之后的确认模式
 * */
public enum AckMode {

    /**
     * Send ack-response automatically on each ack-request
     * <b>skip</b> exceptions during packet handling
     */
    AUTO,//自动确认，跳过异常

    /**
     * Send ack-response automatically on each ack-request
     * only after <b>success</b> packet handling
     */
    AUTO_SUCCESS_ONLY,//成功时，确认

    /**
     * Turn off auto ack-response sending.
     * Use AckRequest.sendAckData to send ack-response each time.
     *
     */
    MANUAL//人工确认

}
