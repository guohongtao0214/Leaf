## Leaf
解析美团的分布式id框架Leaf-MySQL版本

## Introduction
### 数据库自增ID存在固有的弊端：
* 导入旧数据，可能会ID重复，导致导入失败
* 分布式架构中，多个MySQL实例可能会ID重复   

### Leaf秉承的四个特性：
1.全局唯一，绝对不会出现重复的ID，且ID整体趋势递增。  
2.高可用，服务完全基于分布式架构，即使MySQL宕机，也能容忍一段时间的数据库不可用。  
3.高并发低延时，在CentOS 4C8G的虚拟机上，远程调用QPS可达5W+，TP99在1ms内。  
4.接入简单，直接通过公司RPC服务或者HTTP调用即可接入。 

### 服务处理流程
![leaf的处理流程](./pic/leaf处理流程.png)
* Leaf Server 1：从DB加载号段[1，1000]
* Leaf Server 2：从DB加载号段[1001，2000]
* Leaf Server 3：从DB加载号段[2001，3000]  


用户通过Round-robin的方式调用Leaf Server的各个服务，所以某一个Client获取到的ID序列可能是：1，1001，2001，2，1002，2002……  
也可能是：1，2，1001，2001，2002，2003，3，4……当某个Leaf Server号段用完之后，下一次请求就会从DB中加载新的号段，这样保证了每次加载的号段是递增的。  

Leaf数据库中的号段表格式如下：
```sql
CREATE DATABASE leaf
CREATE TABLE `leaf_alloc` (
  `biz_tag` varchar(128)  NOT NULL DEFAULT '',
  `max_id` bigint(20) NOT NULL DEFAULT '1',
  `step` int(11) NOT NULL,
  `description` varchar(256)  DEFAULT NULL,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`biz_tag`)
) ENGINE=InnoDB;
```

Leaf Server加载号段的SQL语句如下：

```sql
Begin
UPDATE table SET max_id=max_id+step WHERE biz_tag=xxx
SELECT tag, max_id, step FROM table WHERE biz_tag=xxx
Commit
```
最巧妙的点，用了数据库事务的原子性保证了线程安全。

### 双Buffer
如果发生数据库宕机或者发生主从切换，会导致一段时间的获取id的服务不能使用，为此美团进行了优化，采用异步更新的策略，通过双Buffer的  
机制，无论何时数据库出现问题，都能有一个Buffer的号段可以正常对外提供服务，只要数据库在一个Buffer的下发周期内恢复，就不会影响整个  
Leaf的可用性。

![leaf的处理流程](./pic/双Buffer.png)

后续代码分析中会做进一步的讨论。

### Leaf动态调整Step
假设服务QPS为Q，号段长度为L，号段更新周期为T，那么Q * T = L。最开始L长度是固定的，导致随着Q的增长，T会越来越小。但是Leaf本质的需求  
是希望T是固定的。那么如果L可以和Q正相关的话，T就可以趋近一个定值了。所以Leaf每次更新号段的时候，根据上一次更新号段的周期T和号段长度step，  
来决定下一次的号段长度nextStep：
* T < 15min，nextStep = step * 2
* 15min < T < 30min，nextStep = step
* T > 30min，nextStep = step / 2

后续代码中会做进一步的论证。

 