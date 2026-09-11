package com.llmgateway.service;

import com.llmgateway.dto.auth.AuthResponse;
import com.llmgateway.dto.auth.LoginRequest;
import com.llmgateway.dto.auth.RegisterRequest;
import com.llmgateway.dto.auth.UserDto;
import com.llmgateway.dto.auth.WalletDto;
import com.llmgateway.entity.User;
import com.llmgateway.entity.UserRole;
import com.llmgateway.entity.Wallet;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

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
     * Kiểm tra định dạng email tiêu chuẩn RFC 5322 cơ bản.
     */
    public static boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    /**
     * Chuẩn hoá email: trim và chuyển về lowercase theo Locale.ROOT.
     */
    public static String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String rawEmail = request.getEmail();
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalArgumentException("Email không được để trống!");
        }

        String email = normalizeEmail(rawEmail);
        if (!isValidEmail(email)) {
            throw new IllegalArgumentException("Định dạng email không hợp lệ: " + rawEmail.trim());
        }

        // Kiểm tra trùng lặp email không phân biệt hoa thường
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new IllegalArgumentException("Email '" + email + "' đã tồn tại trên hệ thống!");
        }

        // Kiểm tra độ dài mật khẩu tối thiểu 8 ký tự (không trim)
        if (request.getPassword() == null || request.getPassword().length() < 8) {
            throw new IllegalArgumentException("Mật khẩu phải có ít nhất 8 ký tự!");
        }

        // 1. Băm mật khẩu bằng BCrypt (không trim để bảo toàn ký tự)
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        // 2. Lưu User vào CSDL với role mặc định luôn là USER
        String fullName = request.getFullName();
        if (fullName == null || fullName.isBlank()) {
            fullName = email;
        }
        User user = new User(email, hashedPassword, fullName.trim(), UserRole.USER);
        user = userRepository.save(user);

        // 3. Tự động cấp ví ảo $10,000 vốn ban đầu (ACID)
        Wallet wallet = new Wallet(user.getId());
        wallet = walletRepository.save(wallet);

        log.info("USER REGISTERED | userId={} | email={} | walletId={}", user.getId(), user.getEmail(), wallet.getId());

        // 4. Sinh JWT token định danh bằng email đã chuẩn hoá
        String token = jwtUtil.generateToken(user.getEmail(), user.getId());

        UserDto userDto = new UserDto(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(), user.getRole().name(), user.getCreatedAt());
        WalletDto walletDto = new WalletDto(wallet.getId(), wallet.getUserId(), wallet.getBalanceUsd(), wallet.getInitialBalance());

        return new AuthResponse(token, userDto, walletDto, "Đăng ký tài khoản và khởi tạo ví ảo $10,000 thành công!");
    }

    /**
     * Đăng nhập:
     * 1. Kiểm tra định dạng email và chuẩn hoá
     * 2. Tìm user theo email (case-insensitive)
     * 3. Kiểm tra mật khẩu băm
     * 4. Lấy thông tin ví ảo
     * 5. Sinh JWT Token
     */
    public AuthResponse login(LoginRequest request) {
        String rawEmail = request.getEmail();
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalArgumentException("Email không được để trống!");
        }

        String email = normalizeEmail(rawEmail);
        if (!isValidEmail(email)) {
            throw new IllegalArgumentException("Định dạng email không hợp lệ: " + rawEmail.trim());
        }

        // Thông báo lỗi chung, tránh hỗ trợ dò quét tài khoản
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("Email hoặc mật khẩu không chính xác!"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Email hoặc mật khẩu không chính xác!");
        }

        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    Wallet newWallet = new Wallet(user.getId());
                    return walletRepository.save(newWallet);
                });

        String token = jwtUtil.generateToken(user.getEmail(), user.getId());
        UserDto userDto = new UserDto(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(), user.getRole().name(), user.getCreatedAt());
        WalletDto walletDto = new WalletDto(wallet.getId(), wallet.getUserId(), wallet.getBalanceUsd(), wallet.getInitialBalance());

        log.info("USER LOGGED IN | userId={} | email={}", user.getId(), user.getEmail());

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
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin email tài khoản!"));

        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseGet(() -> walletRepository.save(new Wallet(user.getId())));

        UserDto userDto = new UserDto(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(), user.getRole().name(), user.getCreatedAt());
        WalletDto walletDto = new WalletDto(wallet.getId(), wallet.getUserId(), wallet.getBalanceUsd(), wallet.getInitialBalance());

        return new AuthResponse(token, userDto, walletDto, "Lấy thông tin tài khoản thành công!");
    }
}
