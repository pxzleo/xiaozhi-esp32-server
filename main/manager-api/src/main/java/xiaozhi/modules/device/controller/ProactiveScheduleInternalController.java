package xiaozhi.modules.device.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.device.proactive.ProactiveScheduleDTOs.*;
import xiaozhi.modules.device.proactive.ProactiveScheduleService;

@RestController @Validated @RequestMapping("/config/proactive/schedules")
public class ProactiveScheduleInternalController {
    private final ProactiveScheduleService service;
    public ProactiveScheduleInternalController(ProactiveScheduleService service){this.service=service;}
    @PostMapping public Result<View> register(@Valid @RequestBody Register request){
        return new Result<View>().ok(service.register(request)); }
    @PostMapping("/{id}:trigger") public Result<View> trigger(
            @PathVariable @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String id,
            @Valid @RequestBody Trigger request){ return new Result<View>().ok(service.trigger(id,request)); }
    @PostMapping("/trigger-by-source") public Result<View> triggerBySource(
            @Valid @RequestBody SourceTrigger request){
        return new Result<View>().ok(service.triggerBySource(request)); }
    @PostMapping("/{id}:action") public Result<View> action(
            @PathVariable @Pattern(regexp="^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String id,
            @Valid @RequestBody Action request){ return new Result<View>().ok(service.action(id,request)); }
    @PostMapping("/action-by-source") public Result<View> actionBySource(
            @Valid @RequestBody SourceAction request){
        return new Result<View>().ok(service.actionBySource(request)); }
    @GetMapping("/device-sync/{mac}") public Result<SyncResponse> deviceSync(
            @PathVariable @Size(max=50) String mac,
            @RequestParam(name="since_revision",defaultValue="0") @Min(0) long since,
            @RequestParam(defaultValue="100") @Min(1) @Max(100) int limit) {
        return new Result<SyncResponse>().ok(service.sync(mac,since,limit));
    }
    @GetMapping("/device-actions/{mac}") public Result<ActionsResponse> deviceActions(
            @PathVariable @Size(max=50) String mac,
            @RequestParam(name="after_revision",defaultValue="0") @Min(0) long after,
            @RequestParam(defaultValue="100") @Min(1) @Max(100) int limit) {
        return new Result<ActionsResponse>().ok(service.actions(mac,after,limit));
    }
    @PostMapping("/device-actions/{mac}:ack") public Result<AckResponse> acknowledge(
            @PathVariable @Size(max=50) String mac,@Valid @RequestBody AckRequest request) {
        return new Result<AckResponse>().ok(service.acknowledge(mac,request));
    }
}
