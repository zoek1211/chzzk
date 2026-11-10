package kr.devcourse.chzzk.member;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 로그인한 회원. 컨트롤러에서는 {@code @AuthenticationPrincipal MemberPrincipal me}로 받는다
 * (비로그인이면 null). 회원 id는 {@link #memberId()}, 닉네임은 {@link #nickname()}.
 */
public final class MemberPrincipal implements UserDetails {

    private final Long memberId;
    private final String loginId;
    private final String nickname;
    private final String passwordHash;

    MemberPrincipal(Member member) {
        this.memberId = member.getId();
        this.loginId = member.getLoginId();
        this.nickname = member.getNickname();
        this.passwordHash = member.getPasswordHash();
    }

    public Long memberId() {
        return memberId;
    }

    public String nickname() {
        return nickname;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginId;
    }
}
