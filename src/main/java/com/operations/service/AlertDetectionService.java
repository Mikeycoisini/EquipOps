package com.operations.service;

/** 告警后台检测入口；当前仅教学骨架，尚未启用自动执行。 */
public interface AlertDetectionService {
    // 声明一次导出故障检测，不依赖浏览器请求或当前Session。
    void detectExportFailures();
}
