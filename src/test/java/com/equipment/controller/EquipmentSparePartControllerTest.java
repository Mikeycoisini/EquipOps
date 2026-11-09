package com.equipment.controller;

import com.common.response.BusinessExceptionHandler;
import com.equipment.service.EquipmentSparePartService;
import com.equipops.domain.dto.equipment.EquipmentSparePartCreateDto;
import java.util.List;
import java.util.function.BiFunction;
import com.equipops.domain.vo.EquipmentSparePartVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立MVC契约测试，不加载安全链或数据库。 */
class EquipmentSparePartControllerTest {
    @Test void 路径设备及正文备件正确传递并返回关联资料() throws Exception {
        var controller=new EquipmentSparePartController(createService((id,request)->{
            assertEquals(20L,id);assertEquals(30L,request.getSparePartId());
            var value=new EquipmentSparePartVO();value.setId(500L);value.setEquipmentId(20L);value.setSparePartId(30L);value.setSparePartName("滤芯");return value;
        }));
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(post("/api/equipment/20/compatible-spare-parts").contentType(MediaType.APPLICATION_JSON).content("{\"sparePartId\":30}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(500)).andExpect(jsonPath("$.data.sparePartName").value("滤芯"));
    }
    @Test void 无效输入不进入业务层() throws Exception {
        var controller=new EquipmentSparePartController(createService((id,request)->{throw new AssertionError("无效请求不能调用业务");}));
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new BusinessExceptionHandler()).build();
        for(String body:new String[]{"{}","{\"sparePartId\":null}","{\"sparePartId\":0}","{\"sparePartId\":30.5}","{\"sparePartId\":\"30\"}"}) {
            mvc.perform(post("/api/equipment/20/compatible-spare-parts").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
    }
    @Test void 重复关联返回统一409响应() throws Exception {
        var controller=new EquipmentSparePartController(createService((id,request)->{throw new ResponseStatusException(HttpStatus.CONFLICT,"该设备已关联此备件");}));
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(post("/api/equipment/20/compatible-spare-parts").contentType(MediaType.APPLICATION_JSON).content("{\"sparePartId\":30}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    }
    private EquipmentSparePartService createService(BiFunction<Long, EquipmentSparePartCreateDto, EquipmentSparePartVO> create) {
        return new EquipmentSparePartService() {
            @Override public EquipmentSparePartVO createRelation(Long id, EquipmentSparePartCreateDto request) {
                return create.apply(id, request);
            }
            @Override public void removeRelation(Long equipmentId, Long sparePartId) {
                throw new AssertionError("POST不能解除关联");
            }
            @Override public List<EquipmentSparePartVO> listRelationsBySparePart(Long id) {
                throw new AssertionError("POST不能调用反向查询");
            }
            @Override public List<EquipmentSparePartVO> listRelations(Long id) {
                throw new AssertionError("POST不能调用列表查询");
            }
        };
    }

}
