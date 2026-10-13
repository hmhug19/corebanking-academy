package vn.com.coreacademy.identity.service;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.repository.UserRepository;

import java.util.List;
import java.util.Locale;

@Service
public class LoginUserResolver {
    private final UserRepository users;

    public LoginUserResolver(UserRepository users) { this.users = users; }

    public UserAccount resolve(String login) {
        if (login == null || login.isBlank()) throw new UsernameNotFoundException("Invalid credentials");
        String identifier = login.trim();
        List<UserAccount> candidates = users.findLoginCandidates(identifier, identifier.toLowerCase(Locale.ROOT));
        // Never choose arbitrarily when a username collides with another employee ID.
        if (candidates.size() != 1) throw new UsernameNotFoundException("Invalid credentials");
        return candidates.get(0);
    }
}
