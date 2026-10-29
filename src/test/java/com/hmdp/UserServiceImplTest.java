package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    private static final String PHONE = "13800138000";
    private static final String CODE = "123456";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;
    @Mock
    private HashOperations<String, Object, Object> hashes;
    @Mock
    private UserMapper userMapper;
    @Captor
    private ArgumentCaptor<Map<Object, Object>> userMapCaptor;
    @Captor
    private ArgumentCaptor<Map<byte[], byte[]>> serializedMapCaptor;
    @Captor
    private ArgumentCaptor<QueryWrapper<User>> queryCaptor;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
    }

    @AfterEach
    void clearUserHolder() {
        UserHolder.removeUser();
    }

    @ParameterizedTest
    @ValueSource(strings = {"current-token", "Bearer raw-token", " raw-token ", "*"})
    void logoutDeletesOnlyTheExactRawTokenAndClearsUserHolder(String token) {
        holdUser();
        String currentKey = LOGIN_USER_KEY + token;
        Map<String, Long> sessions = new HashMap<>();
        sessions.put(currentKey, 42L);
        sessions.put(LOGIN_USER_KEY + "raw-token", 42L);
        sessions.put(LOGIN_USER_KEY + "second-device", 42L);
        sessions.put(LOGIN_USER_KEY + "someone-else", 99L);
        Map<String, Long> remainingSessions = new HashMap<>(sessions);
        remainingSessions.remove(currentKey);
        when(redis.delete(anyString())).thenAnswer(invocation ->
                sessions.remove(invocation.getArgument(0)) != null);

        assertOk(service.logout(token));

        assertEquals(remainingSessions, sessions);
        assertNull(UserHolder.getUser());
        verify(redis).delete(currentKey);
        verifyNoMoreInteractions(redis);
        verifyNoInteractions(userMapper, hashes, values);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " \t\r\n", "\u3000"})
    void logoutWithBlankTokenIsSuccessfulWithoutRedisAccess(String token) {
        holdUser();

        assertOk(service.logout(token));

        assertNull(UserHolder.getUser());
        verifyNoInteractions(redis, userMapper, hashes, values);
    }

    @Test
    void repeatedLogoutIsSuccessfulWhenTheKeyIsAlreadyAbsent() {
        String key = LOGIN_USER_KEY + "current-token";
        when(redis.delete(key)).thenReturn(true, false);
        holdUser();

        assertOk(service.logout("current-token"));
        assertNull(UserHolder.getUser());
        holdUser();
        assertOk(service.logout("current-token"));
        assertNull(UserHolder.getUser());

        verify(redis, times(2)).delete(key);
        verifyNoMoreInteractions(redis);
    }

    @Test
    void logoutClearsUserHolderEvenWhenRedisFailsWithoutHidingTheFailure() {
        holdUser();
        RedisConnectionFailureException failure = new RedisConnectionFailureException("unavailable");
        when(redis.delete(LOGIN_USER_KEY + "current-token")).thenThrow(failure);

        assertSame(failure, assertThrows(RedisConnectionFailureException.class,
                () -> service.logout("current-token")));

        assertNull(UserHolder.getUser());
        verify(redis).delete(LOGIN_USER_KEY + "current-token");
        verifyNoMoreInteractions(redis);
    }

    @Test
    void loginExistingUserSerializesLongIdAndProfileAsStrings() {
        prepareLogin();
        User user = new User().setId(9007199254740993L).setPhone(PHONE)
                .setPassword("not-for-redis").setNickName("测试用户").setIcon("/icons/avatar.png");
        when(userMapper.selectOne(any())).thenReturn(user);
        Map<String, String> expected = new HashMap<>();
        expected.put("id", "9007199254740993");
        expected.put("nickName", "测试用户");
        expected.put("icon", "/icons/avatar.png");

        assertSerializedLogin(service.login(loginForm(), null), expected);
        assertPhoneQuery();
        verifyNoMoreInteractions(userMapper);
    }

    @Test
    void loginOmitsNullProfileFieldsInsteadOfPassingNullToTheSerializer() {
        prepareLogin();
        when(userMapper.selectOne(any())).thenReturn(
                new User().setId(42L).setPhone(PHONE).setNickName(null).setIcon(null));
        Map<String, String> expected = new HashMap<>();
        expected.put("id", "42");

        assertSerializedLogin(service.login(loginForm(), null), expected);
        assertPhoneQuery();
        verifyNoMoreInteractions(userMapper);
    }

    @Test
    void loginNewUserSavesBeforeSerializingTheGeneratedId() {
        prepareLogin();
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(73L);
            return 1;
        });

        Result result = service.login(loginForm(), null);

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(savedUser.capture());
        User user = savedUser.getValue();
        assertEquals(PHONE, user.getPhone());
        assertTrue(user.getNickName().startsWith(USER_NICK_NAME_PREFIX));
        assertEquals(USER_NICK_NAME_PREFIX.length() + 10, user.getNickName().length());
        Map<String, String> expected = new HashMap<>();
        expected.put("id", "73");
        expected.put("nickName", user.getNickName());
        expected.put("icon", "");
        assertSerializedLogin(result, expected);
        assertPhoneQuery();
        verifyNoMoreInteractions(userMapper);
    }

    private void prepareLogin() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LOGIN_CODE_KEY + PHONE)).thenReturn(CODE);
        when(redis.opsForHash()).thenReturn(hashes);
    }

    private LoginFormDTO loginForm() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(PHONE);
        form.setCode(CODE);
        return form;
    }

    private void assertPhoneQuery() {
        verify(userMapper).selectOne(queryCaptor.capture());
        QueryWrapper<User> query = queryCaptor.getValue();
        assertTrue(query.getSqlSegment().contains("phone ="));
        assertTrue(query.getParamNameValuePairs().containsValue(PHONE));
    }

    private void assertSerializedLogin(Result result, Map<String, String> expected) {
        assertTrue(result.getSuccess());
        assertTrue(result.getData() instanceof String);
        String token = (String) result.getData();
        assertTrue(token.matches("[0-9a-f]{32}"));
        String key = LOGIN_USER_KEY + token;
        verify(hashes).putAll(eq(key), userMapCaptor.capture());
        Map<Object, Object> userMap = userMapCaptor.getValue();
        assertEquals(expected, userMap);
        userMap.forEach((field, value) -> {
            assertTrue(field instanceof String);
            assertTrue(value instanceof String);
        });
        verify(redis).expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // Execute real StringRedisTemplate hash serialization, but never open a network connection.
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        StringRedisTemplate realTemplate = new StringRedisTemplate(connectionFactory);
        realTemplate.opsForHash().putAll(key, userMap);
        verify(connection).hMSet(eq(key.getBytes(UTF_8)), serializedMapCaptor.capture());
        Map<String, String> serialized = new HashMap<>();
        serializedMapCaptor.getValue().forEach((field, value) ->
                serialized.put(new String(field, UTF_8), new String(value, UTF_8)));
        assertEquals(expected, serialized);
    }

    private void holdUser() {
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);
    }

    private void assertOk(Result result) {
        assertTrue(result.getSuccess());
        assertNull(result.getErrorMsg());
        assertNull(result.getData());
    }
}
