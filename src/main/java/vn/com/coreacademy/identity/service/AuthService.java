package vn.com.coreacademy.identity.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.com.coreacademy.identity.dto.LoginRequest;
import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.TokenResponse;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.mapper.UserMapper;
import vn.com.coreacademy.identity.repository.UserRepository;

@Service
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final AuthorizationService authorization;
    private final TokenService tokens;
    private final UserMapper userMapper;

    public AuthService(AuthenticationManager authenticationManager, UserRepository users,
                       AuthorizationService authorization, TokenService tokens, UserMapper userMapper) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.authorization = authorization;
        this.tokens = tokens;
        this.userMapper = userMapper;
    }

    public TokenResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        UserAccount user = users.findById(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return tokens.issue(user);
    }

    @Transactional(readOnly = true)
    public MeResponse me(String userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new MeResponse(userMapper.toInfo(user), authorization.rights(userId));
    }
}
