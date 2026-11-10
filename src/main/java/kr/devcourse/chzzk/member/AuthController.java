package kr.devcourse.chzzk.member;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 가입·로그인 화면과 가입 처리. 로그인·로그아웃 처리 자체는 Spring Security 필터(POST /login, POST /logout)가 한다. */
@Controller
class AuthController {

    private final MemberService members;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    AuthController(MemberService members) {
        this.members = members;
    }

    @GetMapping("/signup")
    String signUpForm() {
        return "member/signup";
    }

    @PostMapping("/signup")
    String signUp(@RequestParam String loginId, @RequestParam String password, @RequestParam String nickname,
                  HttpServletRequest request, HttpServletResponse response) {
        Member member = members.signUp(loginId, password, nickname);
        logIn(member, request, response);
        return "redirect:/";
    }

    @ExceptionHandler(InvalidSignUpException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String rejectSignUp(InvalidSignUpException e, HttpServletRequest request, Model model) {
        model.addAttribute("errors", e.getReasons());
        model.addAttribute("loginId", request.getParameter("loginId"));
        model.addAttribute("nickname", request.getParameter("nickname"));
        return "member/signup";
    }

    @GetMapping("/login")
    String loginForm(@RequestParam(required = false) String error, Model model) {
        model.addAttribute("failed", error != null);
        return "member/login";
    }

    private void logIn(Member member, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        MemberPrincipal principal = new MemberPrincipal(member);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
