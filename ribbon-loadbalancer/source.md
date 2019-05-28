# 名词解释
NIWS（Netflix Internal Web Service）


# Ping
## IPingStrategy

对所有实例进行ping操作的策略：串行单线程轮询还是并发多线程ping

核心方法：pingServers 

`可以考虑ping操作的最大执行时间`

## IPing

具体的ping操作实现。

核心方法：isAlive  

如DummyPing(直接返回true)、PingUrl(发起http请求)

## Pinger

每10s对所有实例进行一次ping操作，根据ping的结果，更新服务列表upServerList（ping成功加入可用服务列表中）

成员变量IPingStrategy

# IRule
Ribbon提供了各种负载均衡的规则，包括如下几个：

- RandomRule：根据所有的服务列表总数随机获取一个索引index，然后从所有可用服务列表中根据该index获取server。

- RoundRobinRule：轮询获取server

- ZoneAvoidanceRule：从所有zone中剔除worst区域，获得可用的zone列表，再从可用的zone列表中，根据各个zone的实例个数随机选择一个zone（基于权重的随机）。

# PrimeConnections
建立连接，可以根据实际情况实现IPrimeConnection接口。建立连接的过程是异步的。


# BaseLoadBalancer
主要的成员变量：
- IClientConfig
- IPing：开启周期性ping任务
- IRule
- PrimeConnections
- LoadBalancerStats

# DynamicServerListLoadBalancer
在BaseLoadBalancer的基础上，加入成员变量：
- ServerList
- ServerListFilter
- ServerListUpdater

# ServerListUpdater
默认实现PollingServerListUpdater，启动周期性任务（30s，调用ServerListUpdater.UpdateAction的doUpdate方法）对服务列表进行更新。

ServerListUpdater.UpdateAction具体的服务实例更新实现。

# ServerList
默认实现是DiscoveryEnabledNIWSServerList，通过Netflix的注册中心Eureka获取服务列表。

# ServerListFilter
## ZoneAffinityServerListFilter
### 核心成员变量/属性：
- zoneAffinity 是否开启区域感知功能  Affinity:a close relationship between two things because of qualities or features that they share，翻译为：类同；密切关系
- zoneExclusive 是否开启区域排他功能：只要为true，则仅可调用本区域的服务实例
- Predicate: ZoneAffinityPredicate
探测服务端的zone是否跟该客户端在同一个zone中。核心方法：apply，如果服务端跟该客户端在同一个zone，返回true，否则返回false。
- activeReqeustsPerServerThreshold // 实例平均负载阈值，默认0.6
- blackOutServerPercentageThreshold // 故障实例百分比阈值（断路器断开数 / 实例数量），默认0.8
- availableServersThreshold // 最小可用实例数阈值（实例数量 - 断路器断开数），默认2
- overrideCounter // 跨zone访问的次数
### 核心方法/逻辑
- getFilteredListOfServers 

    根据ServerList获取的所有实例列表，使用ZoneAffinityPredicate过滤出本机房的实例列表。
    - 如果zoneAffinity和zoneExclusive均为false，则不能使用本机房的实例列表，转为使用ServerList获取的所有实例列表，这种情况不会对overrideCounter递增。
    - 如果zoneExclusive为true，则必须使用本机房的实例列表，这种情况不会对overrideCounter递增。
    - 如果shouldEnableZoneAffinity返回false，说明不应该选择本zone的实例，又如果zoneAffinity为true，则对overrideCounter递增，表示跨zone访问。

- shouldEnableZoneAffinity

    获取这些过滤后的同区域实例的基础指标（包含了：实例数量、断路器断开数、活动请求数(活跃连接数)、实例平均负载等），根据一系列的算法求出下面的几个评价值并与设置的阈值对比（下面的为默认值），若有一个条件符合，就不启用“区域感知”过滤的服务实例清单，转为在所有zone中选择。这一算法实现对于集群出现区域故障时，依然可以依靠其他区域的实例进行正常服务提供了完善的高可用保障。

    - blackOutServerPercentage：故障实例百分比（断路器断开数 / 实例数量） >= 0.8
    - activeReqeustsPerServer：实例平均负载 >= 0.6
    - availableServers：可用实例数（实例数量 - 断路器断开数） < 2

# Predicate 
"断言"有两种实现：
- ZoneAffinityPredicate 探测服务端的zone是否跟该客户端在同一个zone中。核心方法：apply，如果服务端跟该客户端在同一个zone，返回true，否则返回false。
- ZoneAvoidancePredicate 探测服务端的zone内的实例是不是最差的，从所有实例中过滤掉最差的实例。

# IClientConfigAware
很多LB相关的属性/成员变量，通过ClientFactory使用反射方式实例化，并且如果该对象是IClientConfigAware，则会调用initWithNiwsConfig进行初始化。这些属性/成员变量包括：ServerListFilter

# X. 统计数据

## X.1 LoadBalancerStats
com.netflix.loadbalancer.LoadBalancerStats
### X.1.1 属性/成员变量
ServerStats也有下面4个指标。
- connectionFailureThreshold // 连接失败阈值， 默认值3
- circuitTrippedTimeoutFactor // 熔断器触发超时时间，默认10s
- maxCircuitTrippedTimeout  // 熔断器触发最大超时时间，默认30s
- activeRequestsCountTimeout // 活跃请求数（连接数），有效窗口时间，默认10*60s

- zoneStatsMap  // key: zone   value: 该zone内的ZoneStats
- upServerListZoneMap  // key: zone   value: 该zone内的实例列表

### X.1.2 核心方法
**getZoneSnapshot**

入参：ServerListFilter过滤出来的属于本zone的实例列表。

根据该zone的实例列表，遍历每个实例是否处于熔断状态、活跃的连接数量，计算出该zone实例的平均负载loadPerServer（所有实例的连接数量与非熔断实例个数的比值）、该zone内所有实例的个数、处于熔断状态的实例个数、所有实例的活跃连接数。

## X.2 ZoneSnapshot
同一个机房内的状态数据

final int instanceCount; //实例数量

final double loadPerServer; //实例平均负载（所有实例请求数除以当前可用的实例数，即当前可用实例数的平均处理请求数）
    
final int circuitTrippedCount;  //断路器断开数（实例不可达的数量）
    
final int activeRequestsCount;    //活动请求数（`连接数？`）（默认10分钟内，所有实例的请求数）

## X.3 ZoneStats
表示该zone内的统计数据，依赖ZoneSnapshot中的数据
    
## X.4 ServerStats
单个实例的状态信息
- connectionFailureThreshold // 与该实例建立连接失败的阈值，默认3
- circuitTrippedTimeoutFactor // 该实例处于熔断状态的超时时间阈值（单位:秒），默认10s
- maxCircuitTrippedTimeout // 该实例处于熔断状态的最大超时时间阈值（单位:秒），默认30s
- activeRequestsCountTimeout // 计算该实例请求数的窗口时间（单位:秒），默认10*60s








