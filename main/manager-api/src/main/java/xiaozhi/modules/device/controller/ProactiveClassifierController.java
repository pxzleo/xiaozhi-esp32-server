package xiaozhi.modules.device.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.device.proactive.ProactiveDTOs.ClassifierModelUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.ClassifierModelView;
import xiaozhi.modules.device.proactive.ProactiveMonitorService;

@RestController
@RequestMapping("/proactive/classifier")
@RequiresPermissions("sys:role:superAdmin")
public class ProactiveClassifierController {
    private final ProactiveMonitorService service;

    public ProactiveClassifierController(ProactiveMonitorService service) {
        this.service = service;
    }

    @GetMapping("/model")
    public Result<ClassifierModelView> model() {
        return new Result<ClassifierModelView>().ok(service.classifierModel());
    }

    @PutMapping("/model")
    public Result<ClassifierModelView> saveModel(@Valid @RequestBody ClassifierModelUpdate request) {
        return new Result<ClassifierModelView>().ok(service.saveClassifierModel(request.getModelId()));
    }

    @PostMapping("/model/test")
    public Result<ClassifierModelView> testModel() {
        return new Result<ClassifierModelView>().ok(service.testClassifierModel());
    }
}
