package com.llmgateway.service;

import com.llmgateway.dto.auth.AuthResponse;
import com.llmgateway.dto.auth.LoginRequest;
import com.llmgateway.dto.auth.RegisterRequest;
import com.llmgateway.dto.auth.UserDto;
import com.llmgateway.dto.auth.WalletDto;
import com.llmgateway.entity.User;
import com.llmgateway.entity.Wallet;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository,
                       WalletRepository walletRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Đăng ký tài khoản mới:
     * 1. Kiểm tra email trùng
     * 2. Băm mật khẩu (BCrypt)
     * 3. Tạo User
     * 4. Tự động khởi tạo Ví ảo $10,000 cho User
     * 5. Sinh JWT Token trả về
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email '" + email + "' đã tồn tại trên hệ thống!");
        }

        // Băm mật khẩu
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        // Lưu User vào Oracle DB
        User user = new User(email, hashedPassword, request.getFullName().trim());
        user = userRepository.save(user);

        // Tự động cấp ví ảo $10,000 vốn ban đầu
        Wallet wallet = new Wallet(user.getId());
        wallet = walletRepository.save(wallet);

        log.info("USER REGISTERED | userId={} | email={} | walletId={}", user.getId(), user.getEmail(), wallet.getId());

        // Sinh JWT token
        String token = jwtUtil.generateToken(user.getEmail(), user.getId());

        UserDto userDto = new UserDto(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(), user.getCreatedAt());
        WalletDto walletDto = new WalletDto(wallet.getId(), wallet.getUserId(), wallet.getBalanceUsd(), wallet.getInitialBalance());

        return new AuthResponse(token, userDto, walletDto, "Đăng ký tài khoản và khởi tạo ví ảo $10,000 thành công!");
    }

    /**
     * Đăng nhập:
     * 1. Tìm user theo email
     * 2. Kiểm tra mật khẩu băm
     * 3. Lấy thông tin ví ảo
     * 4. Sinh JWT Token
     */
    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Email hoặc mật khẩu không chính xác!"));

        // So khớp mật khẩu băm
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Email hoặc mật khẩu không chính xác!");
        }

        // Lấy thông tin ví
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    // Nếu chưa có ví thì tự động tạo bổ sung
                    Wallet newWallet = new Wallet(user.getId());
                    return walletRepository.save(newWallet);
                });

        log.info("USER LOGGED IN | userId={} | email={}", user.getId(), user.getEmail());

        // Sinh JWT token
        String token = jwtUtil.generateToken(user.getEmail(), user.getId());

        UserDto userDto = new UserDto(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(), user.getCreatedAt());
        WalletDto walletDto = new WalletDto(wallet.getId(), wallet.getUserId(), wallet.getBalanceUsd(), wallet.getInitialBalance());

        return new AuthResponse(token, userDto, walletDto, "Đăng nhập thành công!");
    }

    /**
     * Lấy thông tin profile và số dư ví hiện tại qua Token
     */
    public AuthResponse getProfile(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Thiếu token xác thực hoặc header không hợp lệ!");
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            throw new IllegalArgumentException("Token không hợp lệ hoặc đã hết hạn!");
        }

        String email = jwtUtil.getEmailFromToken(token);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng!"));

        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseGet(() -> walletRepository.save(new Wallet(user.getId())));

        UserDto userDto = new UserDto(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(), user.getCreatedAt());
        WalletDto walletDto = new WalletDto(wallet.getId(), wallet.getUserId(), wallet.getBalanceUsd(), wallet.getInitialBalance());

        return new AuthResponse(token, userDto, walletDto, "Lấy thông tin tài khoản thành công!");
    }
}
