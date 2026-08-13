package xiaozhi.modules.device.proactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProactiveScheduleRunner {
    private static final Logger LOGGER=LoggerFactory.getLogger(ProactiveScheduleRunner.class);
    private final ProactiveScheduleService service;
    public ProactiveScheduleRunner(ProactiveScheduleService service){this.service=service;}

    @Scheduled(initialDelay=5_000,fixedDelay=1_000)
    public void processDue(){
        try {
            for(int count=0;count<100 && service.processOneDue();count++) { }
        } catch(RuntimeException error) {
            LOGGER.error("权威日程调度失败: type={}",error.getClass().getSimpleName(),error);
        }
    }
}
