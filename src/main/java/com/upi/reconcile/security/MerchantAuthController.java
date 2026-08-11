package com.upi.reconcile.security;

import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class MerchantAuthController {

    private final MerchantRepository merchantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        if (merchantRepository.findByEmail(request.getEmail()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        Merchant merchant = Merchant.builder()
                .merchantId(UUID.randomUUID())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .businessName(request.getBusinessName())
                .createdAt(OffsetDateTime.now())
                .build();

        merchantRepository.save(merchant);

        String token = jwtUtil.generateToken(merchant.getMerchantId());

        return ResponseEntity.ok(AuthResponse.builder().token(token).build());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        Merchant merchant = merchantRepository.findByEmail(request.getEmail()).orElse(null);
        if (merchant == null || !passwordEncoder.matches(request.getPassword(), merchant.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String token = jwtUtil.generateToken(merchant.getMerchantId());

        return ResponseEntity.ok(AuthResponse.builder().token(token).build());
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me() {
        UUID merchantId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Merchant merchant = merchantRepository.findById(merchantId).orElseThrow();

        return ResponseEntity.ok(MeResponse.builder()
                .merchantId(merchant.getMerchantId())
                .email(merchant.getEmail())
                .businessName(merchant.getBusinessName())
                .connectedGateway(merchant.getConnectedGateway())
                .build());
    }
}

@Data
class SignupRequest {
    @NotBlank
    @Email
    private String email;
    @NotBlank
    private String password;
    @NotBlank
    private String businessName;
}

@Data
class LoginRequest {
    @NotBlank
    @Email
    private String email;
    @NotBlank
    private String password;
}

@Data
@Builder
class AuthResponse {
    private String token;
}

@Data
@Builder
class MeResponse {
    private UUID merchantId;
    private String email;
    private String businessName;
    private String connectedGateway;
}
