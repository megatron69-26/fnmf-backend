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

    // ====================================================================================
    // 🎓 [CÂU HỎI BẢO VỆ ĐỒ ÁN: BẢO MẬT & KHỞI TẠO TÀI KHOẢN]
    // ------------------------------------------------------------------------------------
    // CÂU HỎI CỦA GIẢNG VIÊN:
    //   "Mật khẩu người dùng được lưu trữ thế nào trong CSDL Oracle? Làm sao chống lộ lọt
    //    mật khẩu và đảm bảo mỗi user mới luôn được cấp đúng ví ảo $10,000 không bị lỗi?"
    //
    // CÂU TRẢ LỜI CỦA MÃ NGUỒN (CODE TRẢ LỜI):
    //   1. BẢO MẬT: Sử dụng chuẩn băm BCryptPasswordEncoder với Salt ngẫu nhiên 10 vòng.
    //      Mật khẩu gốc không bao giờ được lưu dưới dạng plaintext.
    //   2. TÍNH TOÀN VẸN (ACID): Dùng @Transactional. Nếu tạo User thành công nhưng tạo
    //      Ví ảo ($10,000) bị lỗi thì toàn bộ quá trình tự động Rollback (hủy), tránh tài
    //      khoản 'mồ côi ví'.
    //   3. PHÂN QUYỀN STATELESS: Sinh JWT Token (HMAC-SHA256) chứa UserId và Email với
    //      thời hạn 24 giờ.
    // ====================================================================================
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String identifier = request.getUsername();
        if (identifier == null || identifier.isBlank()) {
            identifier = request.getEmail();
        }
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Tên đăng nhập không được để trống!");
        }
        identifier = identifier.trim().toLowerCase();

        if (userRepository.existsByEmail(identifier)) {
            throw new IllegalArgumentException("Tài khoản '" + identifier + "' đã tồn tại trên hệ thống!");
        }

        // 1. Băm mật khẩu bằng BCrypt (Bảo vệ thông tin người dùng)
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        // 2. Lưu User vào CSDL Oracle/H2
        String fullName = request.getFullName();
        if (fullName == null || fullName.isBlank()) {
            fullName = identifier;
        }
        User user = new User(identifier, hashedPassword, fullName.trim());
        user = userRepository.save(user);

        // 3. Tự động cấp ví ảo $10,000 vốn ban đầu (Bảng WALLETS)
        Wallet wallet = new Wallet(user.getId());
        wallet = walletRepository.save(wallet);

        log.info("USER REGISTERED | userId={} | username={} | walletId={}", user.getId(), user.getEmail(), wallet.getId());

        // 4. Sinh JWT token cho phiên làm việc
        String token = jwtUtil.generateToken(user.getEmail(), user.getId());

        UserDto userDto = new UserDto(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(), user.getCreatedAt());
        WalletDto walletDto = new WalletDto(wallet.getId(), wallet.getUserId(), wallet.getBalanceUsd(), wallet.getInitialBalance());

        return new AuthResponse(token, userDto, walletDto, "Đăng ký tài khoản và khởi tạo ví ảo $10,000 thành công!");
    }

    /**
     * Đăng nhập:
     * 1. Tìm user theo username/email
     * 2. Kiểm tra mật khẩu băm
     * 3. Lấy thông tin ví ảo
     * 4. Sinh JWT Token
     */
    public AuthResponse login(LoginRequest request) {
        String identifier = request.getUsername();
        if (identifier == null || identifier.isBlank()) {
            identifier = request.getEmail();
        }
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Tên đăng nhập không được để trống!");
        }
        identifier = identifier.trim().toLowerCase();

        User user = userRepository.findByEmail(identifier)
                .orElseThrow(() -> new IllegalArgumentException("Tài khoản hoặc mật khẩu không chính xác!"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Tài khoản hoặc mật khẩu không chính xác!");
        }

        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    Wallet newWallet = new Wallet(user.getId());
                    return walletRepository.save(newWallet);
                });

        String token = jwtUtil.generateToken(user.getEmail(), user.getId());
        UserDto userDto = new UserDto(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl(), user.getCreatedAt());
        WalletDto walletDto = new WalletDto(wallet.getId(), wallet.getUserId(), wallet.getBalanceUsd(), wallet.getInitialBalance());

        log.info("USER LOGGED IN | userId={} | username={}", user.getId(), user.getEmail());

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
