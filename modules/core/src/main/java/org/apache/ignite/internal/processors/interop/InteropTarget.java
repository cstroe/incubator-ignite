/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.interop;

import org.apache.ignite.*;

/**
 *
 */
public interface InteropTarget {
    /**
     * @param type Operation type.
     * @param ptr Input data pointer.
     * @param len Input data length.
     * @return Operation result.
     * @throws org.apache.ignite.IgniteCheckedException If failed.
     */
    public int inOp(int type, long ptr, int len) throws IgniteCheckedException;

    /**
     * @param type Operation type.
     * @param ptr Input data pointer.
     * @param len Input data length.
     * @return Result pointer.
     * @throws org.apache.ignite.IgniteCheckedException If failed.
     */
    public long inOutOp(int type, long ptr, int len) throws IgniteCheckedException;

    /**
     * @param type Operation type.
     * @param ptr Input data pointer.
     * @param len Input data length.
     * @param cb Callback address.
     * @param cbData Value passed to callback.
     * @throws org.apache.ignite.IgniteCheckedException If failed.
     */
    public void inOutOpAsync(int type, long ptr, int len, long cb, long cbData)
        throws IgniteCheckedException;
}