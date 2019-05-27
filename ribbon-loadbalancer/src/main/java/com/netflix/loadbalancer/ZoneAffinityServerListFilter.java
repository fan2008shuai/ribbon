package com.netflix.loadbalancer;

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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.netflix.client.IClientConfigAware;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.DefaultClientConfigImpl;
import com.netflix.client.config.IClientConfig;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext.ContextKey;
import com.netflix.config.DynamicDoubleProperty;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.loadbalancer.AbstractServerListFilter;
import com.netflix.loadbalancer.LoadBalancerStats;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ZoneAffinityPredicate;
import com.netflix.loadbalancer.ZoneSnapshot;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.Monitors;

/**
 * This server list filter deals with filtering out servers based on the Zone affinity. 
 * This filtering will be turned on if either {@link CommonClientConfigKey#EnableZoneAffinity} 
 * or {@link CommonClientConfigKey#EnableZoneExclusivity} is set to true in {@link IClientConfig} object
 * passed into this class during initialization. When turned on, servers outside the same zone (as 
 * indicated by {@link Server#getZone()}) will be filtered out. By default, zone affinity 
 * and exclusivity are turned off and nothing is filtered out.
 * 
 * @author stonse
 *
 */
public class ZoneAffinityServerListFilter<T extends Server> extends
        AbstractServerListFilter<T> implements IClientConfigAware {

    //是否开启区域感知功能
    private volatile boolean zoneAffinity = DefaultClientConfigImpl.DEFAULT_ENABLE_ZONE_AFFINITY;
    //是否开启区域排他功能：只要为true，则仅可调用本区域的服务实例
    private volatile boolean zoneExclusive = DefaultClientConfigImpl.DEFAULT_ENABLE_ZONE_EXCLUSIVITY;

    /**
     * 如果zoneAffinity=true, zoneExclusive=true   则仅可调用本区域的服务实例
     * 如果zoneAffinity=true, zoneExclusive=false  则会进一步判断该区域内的服务实例是否满足调度条件
     * 如果zoneAffinity=false, zoneExclusive=true  则仅可调用本区域的服务实例
     * 如果zoneAffinity=false, zoneExclusive=false 则不进行"区域感知"
     */


    //实例平均负载
    private DynamicDoubleProperty activeReqeustsPerServerThreshold;

    //故障实例百分比（断路器断开数 / 实例数量）
    private DynamicDoubleProperty blackOutServerPercentageThreshold;

    //可用实例数（实例数量 - 断路器断开数）
    private DynamicIntProperty availableServersThreshold;

    private Counter overrideCounter;

    //ZoneAffinity区域感知
    private ZoneAffinityPredicate zoneAffinityPredicate = new ZoneAffinityPredicate();
    
    private static Logger logger = LoggerFactory.getLogger(ZoneAffinityServerListFilter.class);
    
    String zone;
        
    public ZoneAffinityServerListFilter() {      
    }
    
    public ZoneAffinityServerListFilter(IClientConfig niwsClientConfig) {
        initWithNiwsConfig(niwsClientConfig);
    }
    
    @Override
    public void initWithNiwsConfig(IClientConfig niwsClientConfig) {
        String sZoneAffinity = "" + niwsClientConfig.getProperty(CommonClientConfigKey.EnableZoneAffinity, false);
        if (sZoneAffinity != null){
            zoneAffinity = Boolean.parseBoolean(sZoneAffinity);
            logger.debug("ZoneAffinity is set to {}", zoneAffinity);
        }
        String sZoneExclusive = "" + niwsClientConfig.getProperty(CommonClientConfigKey.EnableZoneExclusivity, false);
        if (sZoneExclusive != null){
            zoneExclusive = Boolean.parseBoolean(sZoneExclusive);
        }
        if (ConfigurationManager.getDeploymentContext() != null) {
            zone = ConfigurationManager.getDeploymentContext().getValue(ContextKey.zone);
        }
        activeReqeustsPerServerThreshold = DynamicPropertyFactory.getInstance().getDoubleProperty(niwsClientConfig.getClientName() + "." + niwsClientConfig.getNameSpace() + ".zoneAffinity.maxLoadPerServer", 0.6d);
        logger.debug("activeReqeustsPerServerThreshold: {}", activeReqeustsPerServerThreshold.get());
        blackOutServerPercentageThreshold = DynamicPropertyFactory.getInstance().getDoubleProperty(niwsClientConfig.getClientName() + "." + niwsClientConfig.getNameSpace() + ".zoneAffinity.maxBlackOutServesrPercentage", 0.8d);
        logger.debug("blackOutServerPercentageThreshold: {}", blackOutServerPercentageThreshold.get());
        availableServersThreshold = DynamicPropertyFactory.getInstance().getIntProperty(niwsClientConfig.getClientName() + "." + niwsClientConfig.getNameSpace() + ".zoneAffinity.minAvailableServers", 2);
        logger.debug("availableServersThreshold: {}", availableServersThreshold.get());
        overrideCounter = Monitors.newCounter("ZoneAffinity_OverrideCounter");

        Monitors.registerObject("NIWSServerListFilter_" + niwsClientConfig.getClientName());
    }
    
    private boolean shouldEnableZoneAffinity(List<T> filtered) {
        //同时为false，不应该进行"区域感知"
        if (!zoneAffinity && !zoneExclusive) {
            return false;
        }
        //如果"区域排他"，必须"区域感知"，返回本区域的服务地址
        if (zoneExclusive) {
            return true;
        }
        LoadBalancerStats stats = getLoadBalancerStats();
        if (stats == null) {
            return zoneAffinity;
        } else {
            logger.debug("Determining if zone affinity should be enabled with given server list: {}", filtered);
            ZoneSnapshot snapshot = stats.getZoneSnapshot(filtered);
            double loadPerServer = snapshot.getLoadPerServer();
            int instanceCount = snapshot.getInstanceCount();            
            int circuitBreakerTrippedCount = snapshot.getCircuitTrippedCount();
            /**
             * 获取这些过滤后的同区域实例的基础指标（包含了：实例数量、断路器断开数、活动请求数、实例平均负载等），
             * 根据一系列的算法求出下面的几个评价值并与设置的阈值对比（下面的为默认值），若有一个条件符合，
             * 就不启用“区域感知”过滤的服务实例清单。
             * 这一算法实现对于集群出现区域故障时，依然可以依靠其他区域的实例进行正常服务提供了完善的高可用保障
             *
             * blackOutServerPercentage：故障实例百分比（断路器断开数 / 实例数量） >= 0.8
             * activeReqeustsPerServer：实例平均负载 >= 0.6
             * availableServers：可用实例数（实例数量 - 断路器断开数） < 2
             *
             */

            //TODO may throw  DivideByZeroException
            if (((double) circuitBreakerTrippedCount) / instanceCount >= blackOutServerPercentageThreshold.get() 
                    || loadPerServer >= activeReqeustsPerServerThreshold.get()
                    || (instanceCount - circuitBreakerTrippedCount) < availableServersThreshold.get()) {
                logger.debug("zoneAffinity is overriden. blackOutServerPercentage: {}, activeReqeustsPerServer: {}, availableServers: {}", 
                        new Object[] {(double) circuitBreakerTrippedCount / instanceCount,  loadPerServer, instanceCount - circuitBreakerTrippedCount});
                return false;
            } else {
                return true;
            }
            
        }
    }
        
    @Override
    public List<T> getFilteredListOfServers(List<T> servers) {
        if (zone != null && (zoneAffinity || zoneExclusive) && servers !=null && servers.size() > 0){
            //1. 过滤出本zone的服务列表
            List<T> filteredServers = Lists.newArrayList(Iterables.filter(
                    servers, this.zoneAffinityPredicate.getServerOnlyPredicate()));
            //2. 是否使用本zone的服务列表
            if (shouldEnableZoneAffinity(filteredServers)) {
                return filteredServers;
            } else if (zoneAffinity) {
                overrideCounter.increment();
            }
        }
        return servers;
    }

    @Override
    public String toString(){
        StringBuilder sb = new StringBuilder("ZoneAffinityServerListFilter:");
        sb.append(", zone: ").append(zone).append(", zoneAffinity:").append(zoneAffinity);
        sb.append(", zoneExclusivity:").append(zoneExclusive);
        return sb.toString();       
    }
}
