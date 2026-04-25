/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rks.framework.exception;


import com.rks.framework.errorcode.BaseErrorCode;
import com.rks.framework.errorcode.IErrorCode;

/**
 * 远程调用异常 - 第三方服务调用失败
 *
 * <p>
 * RemoteException 用于表示调用第三方服务或远程组件时发生的错误：
 * </p>
 * <ul>
 *   <li>订单调用支付失败</li>
 *   <li>调用 LLM 服务失败</li>
 *   <li>调用向量数据库（Milvus）失败</li>
 *   <li>调用外部 API 超时</li>
 *   <li>网络连接失败</li>
 * </ul>
 *
 * <h2>设计背景</h2>
 * <p>
 * 在微服务架构中，远程调用失败是常见问题。RemoteException 统一了远程调用错误的表示方式，
 * 便于：
 * </p>
 * <ul>
 *   <li>统一监控和告警（远程调用失败率）</li>
 *   <li>重试机制的处理（远程调用通常支持重试）</li>
 *   <li>错误分类统计</li>
 * </ul>
 *
 * <h2>错误码范围</h2>
 * <p>
 * 远程调用异常的默认错误码为 REMOTE_ERROR (C000001)。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * try {
 *     paymentService.pay(order);
 * } catch (Exception e) {
 *     throw new RemoteException("支付服务调用失败", e, ErrorCode.PAYMENT_ERROR);
 * }
 * }</pre>
 *
 * @see AbstractException
 * @see com.rks.framework.errorcode.BaseErrorCode#REMOTE_ERROR
 */
public class RemoteException extends AbstractException {

    public RemoteException(String message) {
        this(message, null, BaseErrorCode.REMOTE_ERROR);
    }

    public RemoteException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    public RemoteException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }

    @Override
    public String toString() {
        return "RemoteException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
