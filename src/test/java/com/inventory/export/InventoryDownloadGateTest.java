package com.inventory.export;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class InventoryDownloadGateTest {
    @Test void 用户与实例配额同时约束且归还幂等(){
        var gate=new InventoryDownloadGate(3,2);var a=gate.acquire(1L);var b=gate.acquire(1L);var c=gate.acquire(2L);
        assertEquals(429,assertThrows(ResponseStatusException.class,()->gate.acquire(3L)).getStatusCode().value());
        a.close();a.close();try(var d=gate.acquire(3L)){assertThrows(ResponseStatusException.class,()->gate.acquire(4L));}
        b.close();c.close();try(var x=gate.acquire(1L);var y=gate.acquire(2L);var z=gate.acquire(3L)){assertThrows(ResponseStatusException.class,()->gate.acquire(4L));}
    }
    @Test void 并发争用不能突破实例总上限()throws Exception{
        var gate=new InventoryDownloadGate(4,2);var pool=Executors.newFixedThreadPool(16);var start=new CountDownLatch(1);var futures=new ArrayList<Future<InventoryDownloadGate.Lease>>();var leases=new ArrayList<InventoryDownloadGate.Lease>();
        try{
            for(int i=0;i<32;i++){long user=i%8+1;futures.add(pool.submit(()->{start.await();try{return gate.acquire(user);}catch(ResponseStatusException e){assertEquals(429,e.getStatusCode().value());return null;}}));}
            start.countDown();for(var future:futures){var lease=future.get(10,TimeUnit.SECONDS);if(lease!=null)leases.add(lease);}assertEquals(4,leases.size());
        }finally{for(var lease:leases)lease.close();pool.shutdownNow();}
        try(var a=gate.acquire(1L);var b=gate.acquire(2L);var c=gate.acquire(3L);var d=gate.acquire(4L)){assertThrows(ResponseStatusException.class,()->gate.acquire(5L));}
    }
}
