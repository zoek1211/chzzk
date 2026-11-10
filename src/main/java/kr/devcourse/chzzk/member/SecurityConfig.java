package kr.devcourse.chzzk.member;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 세션 폼 로그인과 CSRF. 이 단계에서는 모든 화면과 요청을 공개로 두고(권한은 이후 티켓에서 각 엔드포인트가 검사),
 * CSRF만 켠다. CSRF 토큰은 쿠키(XSRF-TOKEN)에 두고 폼은 _csrf 필드, fetch는 X-XSRF-TOKEN 헤더로 보낸다.
 * WebSocket 시그널링 경로(/ws/**)는 CSRF 대상에서 뺀다.
 */
@Configuration
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/live/new").authenticated()
                        // 녹화방송 관리: 로그인 필수(소유자 여부는 컨트롤러가 검사해 403)
                        .requestMatchers("/broadcasts/new", "/broadcasts/*/edit", "/broadcasts/*/delete").authenticated()
                        .requestMatchers(HttpMethod.POST, "/broadcasts").authenticated()
                        .requestMatchers(HttpMethod.POST, "/broadcasts/*/comments", "/live/*/comments",
                                "/comments/*/edit", "/comments/*/delete").authenticated()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/ws/**"))
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/")
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .permitAll());
        return http.build();
    }
}
