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
 * 客户端异常 - 用户端错误
 *
 * <p>
 * ClientException 用于表示用户端错误，通常是由于：
 * </p>
 * <ul>
 *   <li>用户提交参数错误（为空、格式不正确、超出范围等）</li>
 *   <li>用户权限不足</li>
 *   <li>用户请求的数据不存在</li>
 *   <li>用户重复操作（如重复提交）</li>
 * </ul>
 *
 * <h2>错误码范围</h2>
 * <p>
 * 客户端异常的默认错误码为 CLIENT_ERROR (A000001)。
 * 业务可以传入自定义的 IErrorCode 实现更细粒度的错误分类。
 * </p>
 *
 * <h2>使用场景</h2>
 * <pre>{@code
 * // 参数校验失败
 * if (userId == null) {
 *     throw new ClientException("用户ID不能为空");
 * }
 *
 * // 使用自定义错误码
 * throw new ClientException("会话不存在", ErrorCode.SESSION_NOT_FOUND);
 * }</pre>
 *
 * @see AbstractException
 * @see com.rks.framework.errorcode.BaseErrorCode#CLIENT_ERROR
 */
public class ClientException extends AbstractException {

    public ClientException(IErrorCode errorCode) {
        this(null, null, errorCode);
    }

    public ClientException(String message) {
        this(message, null, BaseErrorCode.CLIENT_ERROR);
    }

    public ClientException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    public ClientException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }

    @Override
    public String toString() {
        return "ClientException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
