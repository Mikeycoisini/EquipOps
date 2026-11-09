package com.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ThreadPoolConfig {

    @Bean("exportThreadPool") // 起个名字，对应你要注入的变量名
    public ExecutorService exportThreadPool() {
        // 为了安全，绝对不用 Executors.newFixedThreadPool，必须手动 new，指定有界队列
        return new ThreadPoolExecutor(
                2, // 核心线程数：平时保持2个线程待命
                2, // 最大线程数：最多同时跑4个导出任务
                0L, TimeUnit.SECONDS, // 空闲存活时间
                new ArrayBlockingQueue<>(10), // 有界队列：最多允许排队20个任务
                new ThreadFactoryWithName(), // 自定义线程名字，方便看日志
                new ThreadPoolExecutor.AbortPolicy() // 拒绝策略：队列满了直接抛异常，配合我们第六步的 catch
        );
    }

    // 辅助类：给线程起个好听的名字（比如 export-thread-1）
    static class ThreadFactoryWithName implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger count = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "export-thread-" + count.getAndIncrement());
        }
    }
}