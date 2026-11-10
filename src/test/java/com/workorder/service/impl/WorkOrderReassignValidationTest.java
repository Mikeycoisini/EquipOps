package com.workorder.service.impl;
import com.equipops.domain.dto.workorder.WorkOrderReassignDto;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class WorkOrderReassignValidationTest {
 private WorkOrderReassignDto request(){return new WorkOrderReassignDto(UUID.randomUUID().toString(),0L,12L,null,"责任交接");}
 private void rejects(Long id,WorkOrderReassignDto request){var error=assertThrows(ResponseStatusException.class,()->new WorkOrderReassignServiceImpl().reassign(id,request));assertEquals(400,error.getStatusCode().value());}
 @Test void missingOrInvalidRequestDoesNotReachBusiness(){rejects(1L,null);rejects(0L,request());var r=request();r.setRequestId("not-uuid");rejects(1L,r);}
 @Test void versionMustBeSafeNonnegativeInteger(){var r=request();r.setExpectedVersion(-1L);rejects(1L,r);r.setExpectedVersion(9_007_199_254_740_992L);rejects(1L,r);}
 @Test void assigneeAndOptionalTeamMustBePositive(){var r=request();r.setTargetAssigneeUserId(0L);rejects(1L,r);r=request();r.setTargetTeamId(-1L);rejects(1L,r);}
 @Test void reasonMustBeTrimmedNonemptyAndBounded(){var r=request();r.setReason("   ");rejects(1L,r);r.setReason("x".repeat(1001));rejects(1L,r);}
}
