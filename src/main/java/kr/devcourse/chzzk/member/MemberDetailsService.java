package kr.devcourse.chzzk.member;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Spring Security의 사용자 조회 연동: 아이디로 회원을 찾는다. */
@Service
class MemberDetailsService implements UserDetailsService {

    private final MemberRepository members;

    MemberDetailsService(MemberRepository members) {
        this.members = members;
    }

    @Override
    public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        return members.findByLoginId(loginId)
                .map(MemberPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("no such member"));
    }
}
