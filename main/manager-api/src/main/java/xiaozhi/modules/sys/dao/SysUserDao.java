package xiaozhi.modules.sys.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.sys.entity.SysUserEntity;

/**
 * 系统用户
 */
@Mapper
public interface SysUserDao extends BaseDao<SysUserEntity> {
    @Select("SELECT id FROM sys_user WHERE id=#{userId} FOR UPDATE")
    Long selectIdForUpdate(@Param("userId") Long userId);
}
