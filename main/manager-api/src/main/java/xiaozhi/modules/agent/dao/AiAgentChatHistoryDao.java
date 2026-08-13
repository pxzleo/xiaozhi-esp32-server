package xiaozhi.modules.agent.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;

/**
 * {@link AgentChatHistoryEntity} 智能体聊天历史记录Dao对象
 *
 * @author Goody
 * @version 1.0, 2025/4/30
 * @since 1.0.0
 */
@Mapper
public interface AiAgentChatHistoryDao extends BaseMapper<AgentChatHistoryEntity> {
    record SharedHistoryRow(Long id, String sessionId, Byte chatType, String content,
            java.util.Date createdAt, String deviceId, String deviceAlias, String terminalType) {}

    @Select("""
            SELECT h.id,h.session_id,h.chat_type,h.content,h.created_at,
                   d.id AS device_id,COALESCE(NULLIF(TRIM(d.display_name),''),
                     CASE WHEN NULLIF(TRIM(d.alias),'') IS NOT NULL
                               AND LOWER(REPLACE(TRIM(d.alias),'-',':'))<>LOWER(d.mac_address)
                          THEN TRIM(d.alias) END,
                     CONCAT(CASE WHEN mi.mobile_instance_id IS NULL THEN '小智音箱 · ' ELSE '手机 · ' END,
                       RIGHT(REPLACE(d.mac_address,':',''),4))) AS device_alias,
                   CASE WHEN mi.mobile_instance_id IS NULL THEN 'speaker' ELSE 'mobile' END AS terminal_type
            FROM ai_agent_chat_history h
            INNER JOIN ai_device d ON d.mac_address=h.mac_address AND d.user_id=#{userId}
            LEFT JOIN ai_mobile_instance mi ON mi.device_id=d.id
              AND mi.mobile_instance_id=mi.canonical_instance_id
            WHERE h.chat_type IN (1,2)
              AND h.content IS NOT NULL AND CHAR_LENGTH(TRIM(h.content))>0
              AND (#{beforeId} IS NULL OR h.id < #{beforeId})
            ORDER BY h.id DESC LIMIT #{limit}
            """)
    List<SharedHistoryRow> selectSharedText(@Param("userId") Long userId,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    /**
     * 根据智能体ID删除聊天历史记录
     *
     * @param agentId 智能体ID
     */
    void deleteHistoryByAgentId(String agentId);

    /**
     * 根据智能体ID删除音频ID
     *
     * @param agentId 智能体ID
     */
    void deleteAudioIdByAgentId(String agentId);

    /**
     * 根据智能体ID获取所有音频ID列表
     *
     * @param agentId 智能体ID
     * @return 音频ID列表
     */
    List<String> getAudioIdsByAgentId(String agentId);

    /**
     * 批量删除音频
     *
     * @param audioIds 音频ID列表
     */
    void deleteAudioByIds(@Param("audioIds") List<String> audioIds);
}
