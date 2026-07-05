package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.CreateUserRequest;
import com.erp.modules.iam.domain.dto.SetPasswordRequest;
import com.erp.modules.iam.domain.dto.UpdateUserRequest;
import com.erp.modules.iam.domain.dto.UserDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.password.WeakPasswordException;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link UserServiceImpl} against real Postgres. Covers: username lowercasing,
 * duplicate username rejection, weak-password rejection, is_root immutability, disable/enable/unlock
 * lifecycle, disabling a root user refusal, and setPasswordByUid policy + hash change.
 *
 * <p>UserServiceImpl reads RequestContext for its tenant-scope guards (getByUid, list,
 * requireInScope) — a root principal is set here so these tests exercise the org-wide/root path
 * rather than tripping the tenant-isolation 404 guard.
 */
class UserServiceImplIT extends PostgresIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private static final String VALID_PASSWORD = "ValidPass1";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        // A real, persisted root user — the audit aspect FKs actor_user_id to app_users(id), so a
        // synthetic id (e.g. 0L) trips fk_audit_log_actor on any audited write in these tests.
        AppUser itRoot = new AppUser("usr_it_root", passwordEncoder.encode(VALID_PASSWORD), "IT Root");
        itRoot.setRoot(true);
        itRoot = users.save(itRoot);
        RequestContext.set(new RequestContext.Principal(
                itRoot.getId(), itRoot.getUsername(), true, null, null, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---------------------------------------------------------------
    // create: username is lowercased
    // ---------------------------------------------------------------

    @Test
    void create_uppercaseUsername_isStoredLowercase() {
        UserDto dto = userService.create(
                new CreateUserRequest("Alice", "Alice Display", VALID_PASSWORD, null, null));
        assertThat(dto.username()).isEqualTo("alice");
        assertThat(users.findByUsername("alice")).isPresent();
    }

    // ---------------------------------------------------------------
    // create: duplicate username -> ConflictException
    // ---------------------------------------------------------------

    @Test
    void create_duplicateUsername_throwsConflict() {
        userService.create(new CreateUserRequest("bob", "Bob One", VALID_PASSWORD, null, null));
        assertThatThrownBy(() ->
                userService.create(new CreateUserRequest("bob", "Bob Two", VALID_PASSWORD, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("bob");
    }

    @Test
    void create_duplicateUsername_caseInsensitive_throwsConflict() {
        userService.create(new CreateUserRequest("carol", "Carol", VALID_PASSWORD, null, null));
        // Upper-case variant must also be rejected (same logical username after lowercasing)
        assertThatThrownBy(() ->
                userService.create(new CreateUserRequest("CAROL", "Carol2", VALID_PASSWORD, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    // ---------------------------------------------------------------
    // create: weak password -> WeakPasswordException (maps to 400)
    // ---------------------------------------------------------------

    @Test
    void create_weakPassword_noDigit_throwsWeakPassword() {
        assertThatThrownBy(() ->
                userService.create(new CreateUserRequest("dave", "Dave", "onlyletters", null, null)))
                .isInstanceOf(WeakPasswordException.class);
    }

    @Test
    void create_weakPassword_tooShort_throwsWeakPassword() {
        assertThatThrownBy(() ->
                userService.create(new CreateUserRequest("eve", "Eve", "Ab1", null, null)))
                .isInstanceOf(WeakPasswordException.class);
    }

    // ---------------------------------------------------------------
    // create: new user has is_root=false and status ACTIVE
    // ---------------------------------------------------------------

    @Test
    void create_newUser_hasIsRootFalseAndStatusActive() {
        UserDto dto = userService.create(
                new CreateUserRequest("frank", "Frank", VALID_PASSWORD, null, null));
        assertThat(dto.isRoot()).isFalse();
        assertThat(dto.status()).isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------
    // getByUid: round-trip; unknown uid -> NotFoundException
    // ---------------------------------------------------------------

    @Test
    void getByUid_existingUser_roundTrips() {
        UserDto created = userService.create(
                new CreateUserRequest("grace", "Grace", VALID_PASSWORD, "grace@test.com", "255700000001"));
        UserDto fetched = userService.getByUid(created.uid());

        assertThat(fetched.uid()).isEqualTo(created.uid());
        assertThat(fetched.username()).isEqualTo("grace");
        assertThat(fetched.email()).isEqualTo("grace@test.com");
        assertThat(fetched.phone()).isEqualTo("255700000001");
    }

    @Test
    void getByUid_unknownUid_throwsNotFound() {
        assertThatThrownBy(() -> userService.getByUid("01HZZZZZZZZZZZZZZZZZZZZZZY"))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------
    // list: includes freshly created users
    // ---------------------------------------------------------------

    @Test
    void list_returnsFreshlyCreatedUsers() {
        userService.create(new CreateUserRequest("zach", "Zach", VALID_PASSWORD, null, null));
        userService.create(new CreateUserRequest("anna", "Anna", VALID_PASSWORD, null, null));

        List<UserDto> all = userService.list();
        List<String> usernames = all.stream().map(UserDto::username).toList();
        assertThat(usernames).contains("anna", "zach");
        // Must be ordered by username (anna before zach)
        assertThat(usernames.indexOf("anna")).isLessThan(usernames.indexOf("zach"));
    }

    // ---------------------------------------------------------------
    // disable: sets status INACTIVE; login thereafter is blocked
    // ---------------------------------------------------------------

    @Test
    void disableByUid_setsStatusInactive() {
        UserDto dto = userService.create(
                new CreateUserRequest("henry", "Henry", VALID_PASSWORD, null, null));
        userService.disableByUid(dto.uid());

        AppUser user = users.findByUid(dto.uid()).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(MasterStatus.INACTIVE);
        assertThat(user.isActive()).isFalse();
    }

    // ---------------------------------------------------------------
    // enable: restores status ACTIVE after disable
    // ---------------------------------------------------------------

    @Test
    void enableByUid_afterDisable_setsStatusActive() {
        UserDto dto = userService.create(
                new CreateUserRequest("irene", "Irene", VALID_PASSWORD, null, null));
        userService.disableByUid(dto.uid());
        userService.enableByUid(dto.uid());

        AppUser user = users.findByUid(dto.uid()).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(MasterStatus.ACTIVE);
    }

    // ---------------------------------------------------------------
    // disable: a root user -> ConflictException (lockout guard)
    // ---------------------------------------------------------------

    @Test
    void disableByUid_rootUser_throwsConflict() {
        // Seed a root user directly: is_root=true, bypass setRoot-via-API restriction
        AppUser rootUser = new AppUser("rootadmin", passwordEncoder.encode(VALID_PASSWORD), "Root Admin");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);

        final String rootUid = rootUser.getUid();
        assertThatThrownBy(() -> userService.disableByUid(rootUid))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("root");
    }

    // ---------------------------------------------------------------
    // unlock: clears failed-login count and lockedUntil
    // ---------------------------------------------------------------

    @Test
    void unlockByUid_clearsLockout() {
        UserDto dto = userService.create(
                new CreateUserRequest("james", "James", VALID_PASSWORD, null, null));

        // Simulate a locked account: set lockedUntil directly via the entity
        AppUser user = users.findByUid(dto.uid()).orElseThrow();
        user.registerFailedLogin(5, 30, Instant.now()); // drives failedLoginCount to 1 and possibly locks
        // Force-lock by exceeding max attempts
        user.registerFailedLogin(5, 30, Instant.now());
        user.registerFailedLogin(5, 30, Instant.now());
        user.registerFailedLogin(5, 30, Instant.now());
        user.registerFailedLogin(5, 30, Instant.now()); // 5th triggers lock
        users.saveAndFlush(user);

        // Verify it is locked
        AppUser locked = users.findByUid(dto.uid()).orElseThrow();
        assertThat(locked.isLocked(Instant.now())).isTrue();

        // Unlock via service
        userService.unlockByUid(dto.uid());

        AppUser unlocked = users.findByUid(dto.uid()).orElseThrow();
        assertThat(unlocked.isLocked(Instant.now())).isFalse();
        assertThat(unlocked.getFailedLoginCount()).isEqualTo(0);
        assertThat(unlocked.getLockedUntil()).isNull();
    }

    // ---------------------------------------------------------------
    // setPasswordByUid: weak password -> WeakPasswordException
    // ---------------------------------------------------------------

    @Test
    void setPasswordByUid_weakPassword_throwsWeakPassword() {
        UserDto dto = userService.create(
                new CreateUserRequest("kate", "Kate", VALID_PASSWORD, null, null));
        assertThatThrownBy(() -> userService.setPasswordByUid(dto.uid(), new SetPasswordRequest("weakonly")))
                .isInstanceOf(WeakPasswordException.class);
    }

    // ---------------------------------------------------------------
    // setPasswordByUid: valid new password -> hash changes; old no longer matches, new matches
    // ---------------------------------------------------------------

    @Test
    void setPasswordByUid_validPassword_changesHash() {
        UserDto dto = userService.create(
                new CreateUserRequest("leo", "Leo", VALID_PASSWORD, null, null));

        String newPassword = "NewSecure9";
        userService.setPasswordByUid(dto.uid(), new SetPasswordRequest(newPassword));

        AppUser user = users.findByUid(dto.uid()).orElseThrow();
        assertThat(passwordEncoder.matches(VALID_PASSWORD, user.getPasswordHash()))
                .as("old password must no longer match")
                .isFalse();
        assertThat(passwordEncoder.matches(newPassword, user.getPasswordHash()))
                .as("new password must match")
                .isTrue();
    }

    // ---------------------------------------------------------------
    // updateByUid: mutable contact fields change
    // ---------------------------------------------------------------

    @Test
    void updateByUid_changesMutableFields() {
        UserDto dto = userService.create(
                new CreateUserRequest("mia", "Mia", VALID_PASSWORD, "old@test.com", null));
        UserDto updated = userService.updateByUid(
                dto.uid(), new UpdateUserRequest("Mia Updated", "new@test.com", "255700000002"));

        assertThat(updated.displayName()).isEqualTo("Mia Updated");
        assertThat(updated.email()).isEqualTo("new@test.com");
        assertThat(updated.phone()).isEqualTo("255700000002");
        assertThat(updated.username()).isEqualTo("mia"); // immutable
    }
}
