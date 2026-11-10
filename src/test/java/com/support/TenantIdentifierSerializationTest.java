package com.support;

import com.equipops.domain.vo.RoleVO;
import com.equipops.domain.vo.SiteVO;
import com.equipops.domain.vo.TenantMemberVO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 企业目录和成员引用ID在浏览器中必须保持BIGINT精度。 */
class TenantIdentifierSerializationTest {
    @Test void 成员企业用户及角色ID均为字符串() throws Exception {
        long id=9_007_199_254_740_993L;
        var member=new TenantMemberVO();member.setId(id);member.setTenantId(id);member.setUserId(id);
        var role=new RoleVO();role.setId(id);member.setRoles(java.util.List.of(role));
        var tree=new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(member));
        for(String field:new String[]{"id","tenantId","userId"}) {
            assertTrue(tree.get(field).isString());assertEquals(Long.toString(id),tree.get(field).asText());
        }
        assertTrue(tree.get("roles").get(0).get("id").isString());
    }
    @Test void 厂区与企业ID均为字符串() throws Exception {
        long id=9_007_199_254_740_993L;
        var site=new SiteVO();site.setId(id);site.setTenantId(id);
        var tree=new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(site));
        assertTrue(tree.get("id").isString());assertTrue(tree.get("tenantId").isString());
        assertEquals(Long.toString(id),tree.get("id").asText());
    }
}
