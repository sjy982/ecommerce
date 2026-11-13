package com.ecommerce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatchers;

import com.ecommerce.auth.filter.JwtAuthenticationFilter;
import com.ecommerce.auth.filter.JwtExceptionFilter;
import com.ecommerce.auth.filter.JwtRefreshTokenFilter;
import com.ecommerce.auth.handler.CustomAuthenticationSuccessHandler;
import com.ecommerce.auth.jwt.JwtProvider;
import com.ecommerce.auth.service.CustomOAuth2UserService;
import com.ecommerce.user.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig  {
    private static final String[] OAUTH2_PATHS = {"/api/auth/login/**", "/oauth2/**", "/login/**"};
    private static final RequestMatcher PUBLIC_PATHS = RequestMatchers.anyOf(
            new AntPathRequestMatcher("/hc"),
            new AntPathRequestMatcher("/api/store", HttpMethod.POST.name()),
            new AntPathRequestMatcher("/api/store/login", HttpMethod.POST.name()),
            new AntPathRequestMatcher("/api/users/join/test", HttpMethod.POST.name())
    );
    private static final String RT_PATH = "/api/users/refresh";
    private static final String JWT_PATH = "/api/**";

    private final ObjectMapper objectMapper;

    private final JwtProvider jwtProvider;

    private final CustomAuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    private final CustomOAuth2UserService customOAuth2UserService;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void applyCommonSettings(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    @Bean
    @Order(1)
    public SecurityFilterChain oauthFilterChain(HttpSecurity http) throws Exception {
        applyCommonSettings(http);
        http.securityMatcher(OAUTH2_PATHS)
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2Login(oauth2 -> oauth2
                    .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                    .successHandler(oAuth2AuthenticationSuccessHandler));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain refreshTokenFilterChain(HttpSecurity http) throws Exception {
        applyCommonSettings(http);
        http.securityMatcher(RT_PATH)
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll())
                .addFilterBefore(new JwtRefreshTokenFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtExceptionFilter(objectMapper), JwtRefreshTokenFilter.class);
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception {
        applyCommonSettings(http);
        http.securityMatcher(PUBLIC_PATHS)
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain jwtFilterChain(HttpSecurity http) throws Exception {
        applyCommonSettings(http);
        http.securityMatcher(JWT_PATH)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "api/users").hasRole(UserRole.TEMP.name())
                        .requestMatchers(HttpMethod.POST, "api/orders/payment").hasRole(UserRole.USER.name())
                        .requestMatchers(HttpMethod.POST, "api/cart/item").hasRole(UserRole.USER.name())
                        .requestMatchers(HttpMethod.GET, "api/cart/items").hasRole(UserRole.USER.name())
                        .requestMatchers("api/admin/**").hasRole(UserRole.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "api/product").hasRole(UserRole.STORE.name())
                        .requestMatchers(HttpMethod.POST, "api/product/*/sale/open").hasRole(UserRole.STORE.name())
                        .requestMatchers("api/notification/**").hasRole(UserRole.STORE.name())
                        .requestMatchers("api/orders/user/**").hasRole(UserRole.USER.name())
                        .requestMatchers("api/orders/store/**").hasRole(UserRole.STORE.name())
                        .anyRequest().permitAll())
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtExceptionFilter(objectMapper), JwtAuthenticationFilter.class);
        return http.build();
    }
}
