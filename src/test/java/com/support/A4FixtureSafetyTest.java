package com.support;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** 夹具只能处理规范UUID，无法用前缀、SQL或路径扩张清理范围。 */
class A4FixtureSafetyTest {
    @Test void fixtureKeyIsExactUuid(){
        String id="9dce1804-3b34-4e95-bf74-c29403a78866";assertEquals(id,A4Fixture.key(id));
        for(String value:new String[]{"","A4_%","../a4","a' OR 1=1",id+"x"})assertThrows(IllegalArgumentException.class,()->A4Fixture.key(value));
        assertThrows(IllegalArgumentException.class,()->A4Fixture.key(null));
    }
}
