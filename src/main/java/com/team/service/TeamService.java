package com.team.service;
import com.team.dto.*;
import com.team.vo.TeamVO;
import java.util.List;
/** 当前企业团队目录与管理员原子变更。 */
public interface TeamService {
    List<TeamVO> list();
    TeamVO detail(Long id);
    TeamVO create(TeamCreateDto request);
    TeamVO update(Long id,TeamUpdateDto request);
    TeamVO members(Long id,TeamMembersDto request);
    TeamVO sites(Long id,TeamSitesDto request);
}
