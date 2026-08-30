package com.llmgateway.config;

import com.llmgateway.dto.auth.RegisterRequest;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final AuthService authService;

    public DataInitializer(UserRepository userRepository, AuthService authService) {
        this.userRepository = userRepository;
        this.authService = authService;
    }

    @Override
    public void run(String... args) {
        try {
            if (userRepository.count() == 0) {
                log.info(">>> Khởi tạo CSDL tự động: Đang tạo tài khoản mặc định khoi.pro@fnmf.com...");
                RegisterRequest req = new RegisterRequest();
                req.setFullName("Đặng Đức Khôi");
                req.setEmail("khoi.pro@fnmf.com");
                req.setPassword("mypassword123");
                authService.register(req);
                log.info(">>> Đã tạo thành công tài khoản 'khoi.pro@fnmf.com' kèm ví $10,000 USD!");
            }
        } catch (Exception e) {
            log.warn("CSDL đã có dữ liệu hoặc không cần khởi tạo: {}", e.getMessage());
        }
    }
}
