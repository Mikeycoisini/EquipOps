package com.team.controller;
import com.common.response.Result;
import com.team.dto.*;
import com.team.service.TeamService;
import com.team.vo.TeamVO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
/** 团队目录及管理员维护协议；所有写成功沿用200响应。 */
@RestController @RequiredArgsConstructor @RequestMapping("/api/teams")
public class TeamController {
    private final TeamService service;
    @GetMapping public Result<List<TeamVO>> list(){return Result.success(service.list());}
    @GetMapping("/{id}") public Result<TeamVO> detail(@PathVariable("id") Long id){return Result.success(service.detail(id));}
    @PostMapping public Result<TeamVO> create(@Valid @RequestBody TeamCreateDto request){return Result.success(service.create(request));}
    @PutMapping("/{id}") public Result<TeamVO> update(@PathVariable("id") Long id,@Valid @RequestBody TeamUpdateDto request){return Result.success(service.update(id,request));}
    @PutMapping("/{id}/members") public Result<TeamVO> members(@PathVariable("id") Long id,@Valid @RequestBody TeamMembersDto request){return Result.success(service.members(id,request));}
    @PutMapping("/{id}/site-scopes") public Result<TeamVO> sites(@PathVariable("id") Long id,@Valid @RequestBody TeamSitesDto request){return Result.success(service.sites(id,request));}
}
