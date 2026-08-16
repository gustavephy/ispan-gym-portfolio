package tw.com.ispan.gym.controller.employee;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.employee.EmployeeReapplyRequest;
import tw.com.ispan.gym.dto.employee.EmployeeRegisterRequest;
import tw.com.ispan.gym.dto.employee.EmployeeRegisterResponse;
import tw.com.ispan.gym.service.employee.EmployeeRegisterService;

@RestController
@RequestMapping("/api/employees")
public class EmployeeRegisterController {

    private final EmployeeRegisterService registerService;

    public EmployeeRegisterController(EmployeeRegisterService registerService) {
        this.registerService = registerService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeRegisterResponse register(@Valid @RequestBody EmployeeRegisterRequest request) {
        return registerService.register(request);
    }

    @PostMapping("/reapply")
    public EmployeeRegisterResponse reapply(@Valid @RequestBody EmployeeReapplyRequest request) {
        return registerService.reapply(request);
    }
}
