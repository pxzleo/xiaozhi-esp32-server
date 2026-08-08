package xiaozhi.modules.device.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventView;
import xiaozhi.modules.device.proactive.ProactiveDTOs.HabitView;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceView;
import xiaozhi.modules.device.proactive.ProactiveEnums.DeliveryStatus;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;
import xiaozhi.modules.device.proactive.ProactiveEnums;
import xiaozhi.modules.device.proactive.ProactiveService;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/device/proactive")
@RequiresPermissions("sys:role:normal")
@Validated
public class DeviceProactiveController {
    private final ProactiveService service;

    public DeviceProactiveController(ProactiveService service) {
        this.service = service;
    }

    @GetMapping("/preferences")
    public Result<List<PreferenceView>> preferences() {
        return new Result<List<PreferenceView>>().ok(service.listPreferences(SecurityUser.getUserId()));
    }

    @GetMapping("/preferences/{deviceId}")
    public Result<PreferenceView> preference(@PathVariable @Size(max = 32) String deviceId) {
        return new Result<PreferenceView>().ok(service.getPreference(SecurityUser.getUserId(), deviceId));
    }

    @PutMapping("/preferences/{deviceId}")
    public Result<PreferenceView> updatePreference(@PathVariable @Size(max = 32) String deviceId,
            @Valid @RequestBody PreferenceUpdate request) {
        return new Result<PreferenceView>().ok(
                service.updatePreference(SecurityUser.getUserId(), deviceId, request));
    }

    @PutMapping("/preferences/{deviceId}/today-silent")
    public Result<PreferenceView> silentToday(@PathVariable @Size(max = 32) String deviceId) {
        return new Result<PreferenceView>().ok(service.silentToday(SecurityUser.getUserId(), deviceId));
    }

    @GetMapping("/events")
    public Result<PageData<EventView>> events(
            @RequestParam(required = false, name = "device_id") @Size(max = 32) String deviceId,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false, name = "delivery_status") String deliveryStatus,
            @RequestParam(required = false, name = "event_type") String eventType,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return new Result<PageData<EventView>>().ok(service.events(SecurityUser.getUserId(), deviceId,
                topic == null ? null : ProactiveEnums.parseWire(Topic.class, topic),
                deliveryStatus == null ? null : ProactiveEnums.parseWire(DeliveryStatus.class, deliveryStatus),
                eventType == null ? null : ProactiveEnums.parseWire(EventType.class, eventType), page, limit));
    }

    @GetMapping("/habits")
    public Result<List<HabitView>> habits(
            @RequestParam(required = false, name = "device_id") @Size(max = 32) String deviceId) {
        return new Result<List<HabitView>>().ok(service.habits(SecurityUser.getUserId(), deviceId));
    }

    @DeleteMapping("/habits/{habitId}")
    public Result<Void> deleteHabit(@PathVariable Long habitId) {
        service.deleteHabit(SecurityUser.getUserId(), habitId);
        return new Result<>();
    }
}
