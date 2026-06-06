package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.CreateUserRequest;
import com.erp.modules.iam.domain.dto.SetPasswordRequest;
import com.erp.modules.iam.domain.dto.UpdateUserRequest;
import com.erp.modules.iam.domain.dto.UserDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.password.PasswordPolicy;
import java.time.Instant;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration (DATA-MODEL §1.4). Usernames are stored lowercased and are org-unique;
 * passwords go through {@link PasswordPolicy} + bcrypt. {@code is_root} is never set here — only
 * {@code BootstrapRunner} mints the root admin — so a created/updated user can never gain super-admin
 * via the API. Disabling a root user is refused (it could lock the deployment out of administration).
 */
@Service
@Transactional
public class UserServiceImpl implements UserService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    public UserServiceImpl(AppUserRepository users,
                           PasswordEncoder passwordEncoder,
                           PasswordPolicy passwordPolicy) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    @Override
    public UserDto create(CreateUserRequest request) {
        String username = request.username().toLowerCase();
        if (users.existsByUsername(username)) {
            throw new ConflictException("Username already exists: " + username);
        }
        passwordPolicy.validate(request.password());

        AppUser user = new AppUser(
                username, passwordEncoder.encode(request.password()), request.displayName());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        // is_root stays false by default — never settable via the API.
        return UserDto.from(users.save(user));
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto getByUid(String uid) {
        return UserDto.from(requireByUid(uid));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDto> list() {
        return users.findAllByOrderByUsername().stream().map(UserDto::from).toList();
    }

    @Override
    public UserDto updateByUid(String uid, UpdateUserRequest request) {
        AppUser user = requireByUid(uid);
        user.setDisplayName(request.displayName());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        return UserDto.from(user); // dirty-checked within the TX
    }

    @Override
    public void disableByUid(String uid) {
        AppUser user = requireByUid(uid);
        if (user.isRoot()) {
            throw new ConflictException("A root administrator cannot be disabled via the API.");
        }
        user.setStatus(MasterStatus.INACTIVE);
    }

    @Override
    public void enableByUid(String uid) {
        requireByUid(uid).setStatus(MasterStatus.ACTIVE);
    }

    @Override
    public void unlockByUid(String uid) {
        requireByUid(uid).unlock();
    }

    @Override
    public void setPasswordByUid(String uid, SetPasswordRequest request) {
        passwordPolicy.validate(request.password());
        requireByUid(uid).changePassword(passwordEncoder.encode(request.password()), Instant.now());
    }

    private AppUser requireByUid(String uid) {
        return Lookups.orNotFound(users.findByUid(uid), "User", uid);
    }
}
