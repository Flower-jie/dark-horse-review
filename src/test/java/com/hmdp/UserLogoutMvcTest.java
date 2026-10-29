package com.hmdp;

import com.hmdp.config.MvcConfig;
import com.hmdp.controller.UserController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserLogoutMvcTest {
    @Mock
    private IUserService delegate;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private HashOperations<String, Object, Object> hashes;

    private AnnotationConfigWebApplicationContext context;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        if (context != null) {
            context.close();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Bearer raw-token", " raw-token "})
    void controllerForwardsTheOptionalHeaderWithoutNormalizingIt(String token) throws Exception {
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", delegate);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(delegate.logout(token)).thenReturn(Result.ok());

        mvc.perform(logoutRequest(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(delegate).logout(token);
        verifyNoMoreInteractions(delegate);
        verifyNoInteractions(redis, hashes);
    }

    @Test
    void tokenMvcRevokesOnlyCurrentTokenAndRejectsRepeatAndProtectedAccess() throws Exception {
        Map<String, Map<Object, Object>> sessions = new HashMap<>();
        sessions.put(LOGIN_USER_KEY + "current-token", userHash());
        sessions.put(LOGIN_USER_KEY + "second-device", userHash());
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(anyString())).thenAnswer(invocation ->
                sessions.getOrDefault(invocation.getArgument(0), Collections.emptyMap()));
        when(redis.delete(anyString())).thenAnswer(invocation ->
                sessions.remove(invocation.getArgument(0)) != null);
        MockMvc mvc = securedMvc();

        mvc.perform(logoutRequest("current-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertNull(UserHolder.getUser());
        assertFalse(sessions.containsKey(LOGIN_USER_KEY + "current-token"));
        assertTrue(sessions.containsKey(LOGIN_USER_KEY + "second-device"));

        // Service idempotency does not exempt HTTP logout from the existing login guard.
        mvc.perform(logoutRequest("current-token")).andExpect(status().isUnauthorized());
        assertNull(UserHolder.getUser());
        mvc.perform(get("/user/me").header("authorization", "current-token"))
                .andExpect(status().isUnauthorized());
        assertNull(UserHolder.getUser());
        mvc.perform(get("/user/me").header("authorization", "second-device"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42));
        assertNull(UserHolder.getUser());
        verify(redis).delete(LOGIN_USER_KEY + "current-token");
        verify(redis, never()).delete(LOGIN_USER_KEY + "second-device");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t"})
    void tokenMvcKeepsBlankHeaderUnauthorizedWithoutRedisAccess(String token) throws Exception {
        MockMvc mvc = securedMvc();

        mvc.perform(logoutRequest(token)).andExpect(status().isUnauthorized());

        assertNull(UserHolder.getUser());
        verifyNoInteractions(redis, hashes);
    }

    @Test
    void tokenMvcDoesNotAcceptALegacySessionInsteadOfAToken() throws Exception {
        MockMvc mvc = securedMvc();
        MockHttpSession session = legacySession();

        mvc.perform(logoutRequest(null).session(session))
                .andExpect(status().isUnauthorized());
        assertNull(UserHolder.getUser());
        mvc.perform(get("/user/me").session(session))
                .andExpect(status().isUnauthorized());

        assertNull(UserHolder.getUser());
        verifyNoInteractions(redis, hashes);
    }

    @Test
    void scannedMvcRegistersOnlyRedisTokenConfiguration() {
        securedMvc();

        Map<String, WebMvcConfigurer> configurers = context.getBeansOfType(WebMvcConfigurer.class);
        assertEquals(1, configurers.size());
        assertSame(context.getBean(MvcConfig.class), configurers.values().iterator().next());
    }

    @Test
    void scannedMvcDoesNotRestoreRevokedTokenAuthenticationFromLegacySession() throws Exception {
        Map<String, Map<Object, Object>> sessions = new HashMap<>();
        sessions.put(LOGIN_USER_KEY + "current-token", userHash());
        sessions.put(LOGIN_USER_KEY + "second-device", userHash());
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(anyString())).thenAnswer(invocation ->
                sessions.getOrDefault(invocation.getArgument(0), Collections.emptyMap()));
        when(redis.delete(anyString())).thenAnswer(invocation ->
                sessions.remove(invocation.getArgument(0)) != null);
        MockMvc mvc = securedMvc();
        MockHttpSession session = legacySession();

        mvc.perform(logoutRequest("current-token").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertNull(UserHolder.getUser());
        assertFalse(sessions.containsKey(LOGIN_USER_KEY + "current-token"));
        assertTrue(sessions.containsKey(LOGIN_USER_KEY + "second-device"));

        mvc.perform(logoutRequest("current-token").session(session))
                .andExpect(status().isUnauthorized());
        assertNull(UserHolder.getUser());
        mvc.perform(get("/user/me").header("authorization", "current-token").session(session))
                .andExpect(status().isUnauthorized());
        assertNull(UserHolder.getUser());
        mvc.perform(logoutRequest(null).session(session))
                .andExpect(status().isUnauthorized());
        assertNull(UserHolder.getUser());
        mvc.perform(get("/user/me").session(session))
                .andExpect(status().isUnauthorized());
        assertNull(UserHolder.getUser());
        mvc.perform(get("/user/me").header("authorization", "second-device"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42));
        assertNull(UserHolder.getUser());
        verify(redis).delete(LOGIN_USER_KEY + "current-token");
        verify(redis, never()).delete(LOGIN_USER_KEY + "second-device");
    }

    private MockHttpServletRequestBuilder logoutRequest(String token) {
        MockHttpServletRequestBuilder request = post("/user/logout");
        if (token != null) {
            request.header("authorization", token);
        }
        return request;
    }

    private Map<Object, Object> userHash() {
        Map<Object, Object> hash = new HashMap<>();
        hash.put("id", "42");
        hash.put("nickName", "current-user");
        return hash;
    }

    private MockHttpSession legacySession() {
        UserDTO user = new UserDTO();
        user.setId(42L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", user);
        return session;
    }

    private MockMvc securedMvc() {
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestMvcConfig.class);
        context.addBeanFactoryPostProcessor(beanFactory -> {
            beanFactory.registerSingleton("stringRedisTemplate", redis);
            beanFactory.registerSingleton("userService", service);
            beanFactory.registerSingleton("userInfoService", mock(IUserInfoService.class));
        });
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Configuration
    @EnableWebMvc
    @ComponentScan(basePackageClasses = MvcConfig.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = WebMvcConfigurer.class))
    static class TestMvcConfig {
        @Bean
        UserController userController() {
            return new UserController();
        }
    }
}
