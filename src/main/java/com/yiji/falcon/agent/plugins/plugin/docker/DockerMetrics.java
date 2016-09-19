/*
 * www.yiji.com Inc.
 * Copyright (c) 2016 All Rights Reserved
 */
package com.yiji.falcon.agent.plugins.plugin.docker;
/*
 * 修订记录:
 * guqiu@yiji.com 2016-08-17 17:15 创建
 */

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.yiji.falcon.agent.util.MD5Util;
import com.yiji.falcon.agent.util.Maths;
import com.yiji.falcon.agent.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * @author guqiu@yiji.com
 */
public class DockerMetrics {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private DockerMetricsUtil dockerRemoteUtil;

    /**
     * 保存第一次监控到的Container的名称
     */
    private static final Set<String> containerNameCache = new HashSet<>();
    /**
     * 容器内运行的命令的解析值
     */
    private static final ConcurrentHashMap<String,String> containerCmdParse = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,String> containerCmd = new ConcurrentHashMap<>();

    /**
     * Docker Metrics 采集
     * @param cAdvisorIp
     * @param cAdvisorPort
     * @throws IOException
     */
    public DockerMetrics(String cAdvisorIp,int cAdvisorPort) throws IOException {
        dockerRemoteUtil = new DockerMetricsUtil(cAdvisorIp,cAdvisorPort);
        logger.info("cAdvisor服务连接数据采集：{}:{}",cAdvisorIp,cAdvisorPort);
    }

    /**
     * 数据采集
     * @return
     */
    public List<CollectObject> getMetrics() throws IOException, InterruptedException {
        List<CollectObject> collectObjectList = new ArrayList<>();
        JSONObject containers = dockerRemoteUtil.getContainersJSON();
        JSONObject machineInfo = dockerRemoteUtil.getMachineInfo();
        Set<String> containerNames = new HashSet<>();
        Set<String> keys = containers.keySet();
        //只保存第一次运行时的容器名称
        boolean saveContainerName = containerNameCache.isEmpty();
        for (String key : keys) {
            JSONObject container = containers.getJSONObject(key);
            String containerId = key.replace("/docker/","");
            JSONArray aliases = container.getJSONArray("aliases");
            String containerName = "";
            for (Object aliase : aliases) {
                if(!containerId.equals(aliase)){
                    containerName = String.valueOf(aliase);
                    break;
                }
            }

            containerNames.add(containerName);
            if(saveContainerName){
                containerNameCache.add(containerName);
            }

            collectObjectList.addAll(getCpuMetrics(containerName,container));
            collectObjectList.addAll(getMemMetrics(containerName,container,machineInfo));
            collectObjectList.addAll(getNetMetrics(containerName,container));
            collectObjectList.add(containerAppMetrics(containerName,containerId));
        }

        //容器可用性
        for (String containerName : containerNameCache) {
            if(containerNames.contains(containerName)){
                collectObjectList.add(new CollectObject(containerName,"availability.container","1",""));
            }else{
                collectObjectList.add(new CollectObject(containerName,"availability.container","0",""));
            }
        }

        return collectObjectList;
    }

    private List<CollectObject> getCpuMetrics(String containerName, JSONObject container) throws IOException, InterruptedException {
        List<CollectObject> collectObjectList = new ArrayList<>();

        boolean hasCpu = container.getJSONObject("spec").getBoolean("has_cpu");
        collectObjectList.add(new CollectObject(containerName,"has_cpu",hasCpu ? "1" : "0",""));
        if(hasCpu){
            JSONArray stats = container.getJSONArray("stats");
            int count = stats.size();
            JSONObject stat = stats.getJSONObject(count - 1);
//            String timestamp = stat.getString("timestamp");
    //        Date date = DateUtil.getParrtenDate(timestamp.substring(0,10) + " " + timestamp.substring(11,19),"yyyy-MM-dd HH:mm:ss");

            JSONObject cpu = stat.getJSONObject("cpu");
            JSONObject usage = cpu.getJSONObject("usage");

            long total = usage.getLong("total");
            long user = usage.getLong("user");

            double userRate =  Maths.div(user,total,5) * 100;

            collectObjectList.add(new CollectObject(containerName,"total.cpu.usage.rate",String.valueOf(userRate),""));
        }

        return collectObjectList;
    }

    /**
     * 容器内的应用监控
     * @param containerName
     * @param idOrName
     * @return
     * @throws IOException
     */
    private CollectObject containerAppMetrics(String containerName,String idOrName) throws IOException {
        CollectObject collectObject = null;

        String cmd = "/bin/ps aux";
        DockerExecResult execResult = dockerRemoteUtil.exec(cmd,idOrName);
        if(execResult.isSuccess()){
            collectObject = new CollectObject(containerName,"availability.container.app","0","");
            String parseMD5 = psCommandMD5(containerName,execResult.getResult());
            String cache = containerCmdParse.get(containerName);
            if(cache == null){
                //存放第一次的解析值,返回1
                containerCmdParse.put(containerName,parseMD5);
                collectObject.setValue("1");
            }else{
                //比较上一次解析值,若完全匹配,则设置1,代表容器内应用是活动的切与第一次监控的活动情况一致
                if(cache.equals(parseMD5)){
                    collectObject.setValue("1");
                }else{
                    logger.warn("与第一次监控的进程活动不同。\n第一次:{}\n现在的:{}",containerCmd.get(containerName + "-first"),containerCmd.get(containerName));
                }
            }
        }else {
            collectObject = new CollectObject(containerName,"availability.container.app","-1","");
            logger.error("Docker container exec {} execute failed : {}",cmd,execResult.getResult());
        }

        return collectObject;
    }

    private List<CollectObject> getNetMetrics(String containerName,JSONObject container){
        List<CollectObject> collectObjectList = new ArrayList<>();

        boolean hasNetwork = container.getJSONObject("spec").getBoolean("has_network");
        collectObjectList.add(new CollectObject(containerName,"has_network",hasNetwork ? "1" : "0",""));

        if(hasNetwork){
            JSONArray stats = container.getJSONArray("stats");
            int count = stats.size();
            JSONObject stat = stats.getJSONObject(count - 1);
            JSONObject network = stat.getJSONObject("network");
            JSONArray interfaces = network.getJSONArray("interfaces");
            for (Object ifObj : interfaces) {
                JSONObject ifJson = (JSONObject) ifObj;
                String ifName = ifJson.getString("name");
                String tag = "ifName=" + ifName;
                long rxBytes = ifJson.getLong("rx_bytes");
                long rxPackets = ifJson.getLong("rx_packets");
                long rxErrors = ifJson.getLong("rx_errors");
                long rxDropped = ifJson.getLong("rx_dropped");
                long txBytes = ifJson.getLong("tx_bytes");
                long txPackets = ifJson.getLong("tx_packets");
                long txErrors = ifJson.getLong("tx_errors");
                long txDropped = ifJson.getLong("tx_dropped");

                collectObjectList.add(new CollectObject(containerName,"net.if.in.bytes",String.valueOf(rxBytes),tag));
                collectObjectList.add(new CollectObject(containerName,"net.if.in.packets",String.valueOf(rxPackets),tag));
                collectObjectList.add(new CollectObject(containerName,"net.if.in.errors",String.valueOf(rxErrors),tag));
                collectObjectList.add(new CollectObject(containerName,"net.if.in.dropped",String.valueOf(rxDropped),tag));

                collectObjectList.add(new CollectObject(containerName,"net.if.out.bytes",String.valueOf(txBytes),tag));
                collectObjectList.add(new CollectObject(containerName,"net.if.out.packets",String.valueOf(txPackets),tag));
                collectObjectList.add(new CollectObject(containerName,"net.if.out.errors",String.valueOf(txErrors),tag));
                collectObjectList.add(new CollectObject(containerName,"net.if.out.dropped",String.valueOf(txDropped),tag));
            }
        }

        return collectObjectList;
    }

    /**
     * 内存
     * @param containerName
     * @param container
     * @return
     * @throws IOException
     */
    private List<CollectObject> getMemMetrics(String containerName,JSONObject container,JSONObject machineInfo) throws IOException {
        List<CollectObject> collectObjectList = new ArrayList<>();

        boolean hasMemory = container.getJSONObject("spec").getBoolean("has_memory");
        collectObjectList.add(new CollectObject(containerName,"has_memory",hasMemory ? "1" : "0",""));

        if(hasMemory){
            long machineMemory = machineInfo.getLong("memory_capacity");//机器总内存
            long limitMemory = container.getJSONObject("spec").getJSONObject("memory").getLong("limit");//容器的内存限制大小
            if(limitMemory > machineMemory || limitMemory < 0){
                //若内存限制大于机器总内存或小于0，使用机器总内存为总内存大小
                limitMemory = machineMemory;
            }
            JSONArray stats = container.getJSONArray("stats");
            int count = stats.size();
            JSONObject stat = stats.getJSONObject(count - 1);
            JSONObject memory = stat.getJSONObject("memory");

            long usage = memory.getLong("usage");
            long cache = memory.getLong("cache");

            collectObjectList.add(new CollectObject(containerName,"mem.size.usage", String.valueOf(Maths.div(usage,1024 * 1024)),""));
            collectObjectList.add(new CollectObject(containerName,"mem.size.cache", String.valueOf(Maths.div(cache,1024 * 1024)),""));
            collectObjectList.add(new CollectObject(containerName,"mem.usage.rate", String.valueOf(Maths.div(usage,limitMemory,5) * 100),""));
            collectObjectList.add(new CollectObject(containerName,"mem.cache.rate", String.valueOf(Maths.div(cache,limitMemory,5) * 100),""));

        }
        return collectObjectList;
    }

    /**
     * ps aux 的命令解析
     * @throws IOException
     * @return
     * 返回命令的MD5标识串
     */
    private String psCommandMD5(String containerName,String msg) throws IOException {
        StringTokenizer st = new StringTokenizer(msg,"\n",false);
        List<List<String>> psParseList = new ArrayList<>();
        while( st.hasMoreElements() ){
            String split = st.nextToken().trim();
            if(!StringUtils.isEmpty(split)){
                String[] ss = split.split("\\s+");
                List<String> list = new ArrayList<>();
                Collections.addAll(list, ss);
                psParseList.add(list);
            }
        }

        List<String> titles = psParseList.get(0);
        List<String> command = new ArrayList<>();

        int commandIndex = titles.indexOf("COMMAND");
        for (int i = 1; i < psParseList.size(); i++) {
            List<String> commandContent = psParseList.get(i);
            StringBuilder sb = new StringBuilder();
            for (int j = commandIndex; j < commandContent.size(); j++) {
                //所有的命令
                String str = commandContent.get(j);
                if(!Pattern.matches("\\w*\\d+:\\d+\\w*",str)){
                    //去掉可能出现的时间值,如 11:00 11:00AM
                    sb.append(commandContent.get(j)).append(" ");
                }
            }
            command.add(sb.toString());
        }

        Collections.sort(command,Collections.reverseOrder());
        final StringBuilder sb = new StringBuilder();
        command.forEach(sb::append);

        String result = sb.toString();

        if(containerCmd.get(containerName) == null){
            containerCmd.put(containerName + "-first", result);
        }
        containerCmd.put(containerName, result);


        return MD5Util.getMD5(result);
    }

    public class CollectObject{
        /**
         * 容器名称
         */
        private String containerName;
        /**
         * 指标名称
         */
        private String metric;
        /**
         * 指标值
         */
        private String value;
        /**
         * 标签
         */
        private String tags;

        CollectObject(String containerName, String metric, String value,String tags) {
            this.containerName = containerName;
            this.metric = metric;
            this.value = value;
            this.tags = tags;
        }

        @Override
        public String toString() {
            return "CollectObject{" +
                    "containerName='" + containerName + '\'' +
                    ", metric='" + metric + '\'' +
                    ", value='" + value + '\'' +
                    ", tags='" + tags + '\'' +
                    '}';
        }

        public String getTags() {
            return tags == null ? "" : tags;
        }

        public void setTags(String tags) {
            this.tags = tags;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getMetric() {
            return metric;
        }

        public void setMetric(String metric) {
            this.metric = metric;
        }

        public String getContainerName() {
            return containerName;
        }

        public void setContainerName(String containerName) {
            this.containerName = containerName;
        }
    }
}
