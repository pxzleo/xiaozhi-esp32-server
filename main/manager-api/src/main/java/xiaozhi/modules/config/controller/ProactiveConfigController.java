package xiaozhi.modules.config.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventStatusUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventClaim;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventUpsert;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventCreateResult;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventView;
import xiaozhi.modules.device.proactive.ProactiveDTOs.HabitObserve;
import xiaozhi.modules.device.proactive.ProactiveDTOs.HabitView;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceView;
import xiaozhi.modules.device.proactive.ProactiveService;
import xiaozhi.modules.device.proactive.ProactiveMonitorService;
import xiaozhi.modules.device.proactive.ProactiveDTOs.ClassifierEvaluate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.ClassifierResult;
import xiaozhi.modules.device.proactive.ProactiveDTOs.MonitorComplete;
import xiaozhi.modules.device.proactive.ProactiveDTOs.MonitorLeaseRequest;
import xiaozhi.modules.device.proactive.ProactiveDTOs.MonitorTask;

@RestController
@RequestMapping("/config/proactive")
@Validated
public class ProactiveConfigController {
    private final ProactiveService service;
    private final ProactiveMonitorService monitorService;

    public ProactiveConfigController(ProactiveService service, ProactiveMonitorService monitorService) {
        this.service = service;
        this.monitorService = monitorService;
    }

    @PostMapping("/monitors/claim")
    public Result<List<MonitorTask>> claimMonitors(@Valid @RequestBody MonitorLeaseRequest request) {
        return new Result<List<MonitorTask>>().ok(
                monitorService.claimDue(request.getLeaseOwner(), request.getLimit()));
    }

    @PostMapping("/monitors/complete")
    public Result<Void> completeMonitor(@Valid @RequestBody MonitorComplete request) {
        monitorService.complete(request);
        return new Result<>();
    }

    @GetMapping("/monitor-events/{eventId}")
    public Result<EventView> monitorEvent(@PathVariable @Size(max = 64) String eventId,
            @RequestParam("mac_address") @NotBlank @Size(max = 50) String macAddress) {
        return new Result<EventView>().ok(service.monitorEvent(macAddress, eventId));
    }

    @PostMapping("/classifier/evaluate")
    public Result<ClassifierResult> evaluate(@Valid @RequestBody ClassifierEvaluate request) {
        return new Result<ClassifierResult>().ok(monitorService.evaluate(request));
    }

    @GetMapping("/preferences/{macAddress}")
    public Result<PreferenceView> preference(@PathVariable @Size(max = 50) String macAddress) {
        return new Result<PreferenceView>().ok(service.getPreferenceByMac(macAddress));
    }

    @PutMapping("/preferences/{macAddress}")
    public Result<PreferenceView> updatePreference(@PathVariable @Size(max = 50) String macAddress,
            @Valid @RequestBody PreferenceUpdate request) {
        return new Result<PreferenceView>().ok(service.updatePreferenceByMac(macAddress, request));
    }

    @PostMapping("/events")
    public Result<EventView> event(@Valid @RequestBody EventUpsert request) {
        return new Result<EventView>().ok(service.upsertEvent(request));
    }

    @PostMapping("/monitor-events")
    public Result<EventCreateResult> monitorEvent(
            @Valid @RequestBody EventUpsert request) {
        return new Result<EventCreateResult>().ok(service.createMonitorEvent(request));
    }

    @PutMapping("/events/{eventId}/status")
    public Result<EventView> eventStatus(@PathVariable @Size(max = 64) String eventId,
            @Valid @RequestBody EventStatusUpdate request) {
        return new Result<EventView>().ok(service.updateEventStatus(eventId, request));
    }

    @PostMapping("/events/{eventId}/claim")
    public Result<Boolean> eventClaim(@PathVariable @Size(max = 64) String eventId,
            @Valid @RequestBody EventClaim request) {
        return new Result<Boolean>().ok(service.claimEvent(eventId, request));
    }

    @PostMapping("/habits/observe")
    public Result<HabitView> observe(@Valid @RequestBody HabitObserve request) {
        return new Result<HabitView>().ok(service.observeHabit(request));
    }

    @GetMapping("/habits/candidates")
    public Result<List<HabitView>> candidates(
            @RequestParam("mac_address") @NotBlank @Size(max = 50) String macAddress) {
        return new Result<List<HabitView>>().ok(service.candidatesByMac(macAddress));
    }
}
