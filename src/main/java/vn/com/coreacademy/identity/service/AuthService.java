package vn.com.coreacademy.identity.service;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.com.coreacademy.identity.dto.LoginRequest;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.dto.FunctionInfo;
import vn.com.coreacademy.organization.repository.OrganizationRepository;
import vn.com.coreacademy.organization.repository.TeamRepository;
import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.TokenResponse;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.mapper.UserMapper;
import vn.com.coreacademy.identity.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final AuthorizationService authorization;
    private final TokenService tokens;
    private final UserMapper userMapper;
    private final OrganizationRepository organizations;
    private final TeamRepository teams;

    public TokenResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        UserAccount user = users.findById(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return tokens.issue(user);
    }

    // Atomic version change revokes all previously issued tokens, across application instances.
    @Transactional
    public void logout(String userId, long tokenVersion) {
        if (users.revokeTokens(userId, tokenVersion) != 1)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token already revoked");
    }

    @Transactional(readOnly = true)
    public MeResponse me(String userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var organization = user.getOrgId() == null ? null : organizations.findById(user.getOrgId()).orElse(null);
        var team = user.getTeamId() == null ? null : teams.findById(user.getTeamId()).orElse(null);
        // Do not display a team belonging to a different organization on an inconsistent profile.
        if (team != null && !java.util.Objects.equals(team.getOrgId(), user.getOrgId())) team = null;
        var rights = authorization.rights(userId);
        return new MeResponse(userMapper.toInfo(user, organization, team),
                rights.roles().stream().map(RoleGrant::roleCode).distinct().sorted().toList(),
                rights.functions().stream().map(FunctionInfo::code).distinct().toList(),
                rights.roles(), rights.functions(), rights.functionScopes());
    }
}
