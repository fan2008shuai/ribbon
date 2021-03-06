/*
*
* Copyright 2013 Netflix, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/
package com.netflix.loadbalancer;

/**
 * Captures the metrics on a Per Zone basis (Zone is modeled after the Amazon Availability Zone)
 * @author awang
 *
 */
public class ZoneSnapshot {
    //实例数量
    final int instanceCount;
    //实例平均负载（所有实例请求数(连接数？)除以当前可用的实例数，即当前可用实例数的平均处理请求数（连接数））
    final double loadPerServer;
    //断路器断开数（实例不可达的数量）
    final int circuitTrippedCount;
    //活跃请求数/连接数（默认10分钟内，所有实例的请求数/连接数）
    final int activeRequestsCount;
    
    public ZoneSnapshot() {
        this(0, 0, 0, 0d);
    }
    
    public ZoneSnapshot(int instanceCount, int circuitTrippedCount, int activeRequestsCount, double loadPerServer) {
        this.instanceCount = instanceCount;
        this.loadPerServer = loadPerServer;
        this.circuitTrippedCount = circuitTrippedCount;
        this.activeRequestsCount = activeRequestsCount;
    }
    
    public final int getInstanceCount() {
        return instanceCount;
    }
    
    public final double getLoadPerServer() {
        return loadPerServer;
    }
    
    public final int getCircuitTrippedCount() {
        return circuitTrippedCount;
    }
    
    public final int getActiveRequestsCount() {
        return activeRequestsCount;
    }

    @Override
    public String toString() {
        return "ZoneSnapshot [instanceCount=" + instanceCount
                + ", loadPerServer=" + loadPerServer + ", circuitTrippedCount="
                + circuitTrippedCount + ", activeRequestsCount="
                + activeRequestsCount + "]";
    }
}
