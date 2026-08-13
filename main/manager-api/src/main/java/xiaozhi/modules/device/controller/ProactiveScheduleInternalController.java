package xiaozhi.modules.device.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
            @PathVariable @Pattern(regexp="^[0-9a-f-]{36}$") String id,
            @Valid @RequestBody Trigger request){ return new Result<View>().ok(service.trigger(id,request)); }
    @PostMapping("/trigger-by-source") public Result<View> triggerBySource(
            @Valid @RequestBody SourceTrigger request){
        return new Result<View>().ok(service.triggerBySource(request)); }
    @PostMapping("/{id}:action") public Result<View> action(
            @PathVariable @Pattern(regexp="^[0-9a-f-]{36}$") String id,
            @Valid @RequestBody Action request){ return new Result<View>().ok(service.action(id,request)); }
    @PostMapping("/action-by-source") public Result<View> actionBySource(
            @Valid @RequestBody SourceAction request){
        return new Result<View>().ok(service.actionBySource(request)); }
}
