package xiaozhi.modules.mobile;

import java.util.List;

import org.springframework.stereotype.Service;

import xiaozhi.modules.agent.dao.AiAgentChatHistoryDao;
import xiaozhi.modules.mobile.MobileSharedHistoryDTOs.Item;
import xiaozhi.modules.mobile.MobileSharedHistoryDTOs.Response;

@Service
public class MobileSharedHistoryService {
    private final MobileEventService authService;
    private final AiAgentChatHistoryDao historyDao;

    public MobileSharedHistoryService(MobileEventService authService,
            AiAgentChatHistoryDao historyDao) {
        this.authService = authService;
        this.historyDao = historyDao;
    }

    public Response list(MobileEventService.MobileAuth auth, Long beforeId, int limit) {
        MobileInstanceEntity instance = authService.authenticate(auth, "voice_session");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<Item> items = historyDao.selectSharedText(instance.getUserId(), beforeId, safeLimit)
                .stream().map(row -> new Item(row.id(), row.sessionId(),
                        Byte.valueOf((byte) 1).equals(row.chatType()) ? "user" : "assistant",
                        row.content(), row.createdAt().getTime(), row.deviceId(),
                        row.deviceAlias(), row.terminalType())).toList();
        return new Response(1, items, items.size() == safeLimit
                ? items.getLast().id() : null);
    }
}
