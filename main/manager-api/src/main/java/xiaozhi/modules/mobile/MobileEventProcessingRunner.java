package xiaozhi.modules.mobile;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MobileEventProcessingRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(MobileEventProcessingRunner.class);
    private final MobileEventProcessingService service;
    private final String owner = "mobile-events-" + UUID.randomUUID().toString().substring(0, 16);

    public MobileEventProcessingRunner(MobileEventProcessingService service) {
        this.service = service;
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 30_000)
    public void process() {
        try {
            service.processBatch(owner, 20);
        } catch (RuntimeException error) {
            LOGGER.error("手机感知事件后台处理失败: type={}",
                    error.getClass().getSimpleName(), error);
        }
    }
}
