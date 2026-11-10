package kr.devcourse.chzzk.member;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 회원 모듈의 공개 인터페이스. 가입 규칙(아이디·비밀번호·닉네임)을 이 모듈이 소유한다. */
@Service
public class MemberService {

    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9]{4,20}");
    private static final int MIN_PASSWORD = 8;
    private static final int MIN_NICKNAME = 2;
    private static final int MAX_NICKNAME = 20;

    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;

    MemberService(MemberRepository members, PasswordEncoder passwordEncoder) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
    }

    /** 규칙을 검사하고 가입시킨다. 위반이나 아이디 중복이면 InvalidSignUpException. */
    public Member signUp(String loginId, String password, String nickname) {
        String nick = nickname == null ? "" : nickname.strip();
        List<String> reasons = new ArrayList<>();
        if (loginId == null || !LOGIN_ID.matcher(loginId).matches()) {
            reasons.add("아이디는 영문 소문자와 숫자 4~20자여야 합니다.");
        }
        if (password == null || password.length() < MIN_PASSWORD) {
            reasons.add("비밀번호는 8자 이상이어야 합니다.");
        }
        if (nick.length() < MIN_NICKNAME || nick.length() > MAX_NICKNAME) {
            reasons.add("닉네임은 2~20자여야 합니다.");
        }
        if (reasons.isEmpty() && members.existsByLoginId(loginId)) {
            reasons.add("이미 사용 중인 아이디입니다.");
        }
        if (!reasons.isEmpty()) {
            throw new InvalidSignUpException(reasons);
        }
        try {
            return members.saveAndFlush(new Member(loginId, passwordEncoder.encode(password), nick));
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 아이디로 가입한 경우
            throw new InvalidSignUpException(List.of("이미 사용 중인 아이디입니다."));
        }
    }

    public Optional<Member> findByLoginId(String loginId) {
        return members.findByLoginId(loginId);
    }

    public Optional<Member> findById(Long id) {
        return members.findById(id);
    }
}
