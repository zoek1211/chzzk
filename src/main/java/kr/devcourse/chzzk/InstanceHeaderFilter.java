package kr.devcourse.chzzk;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 클러스터 모드에서 응답마다 그 요청을 처리한 인스턴스 이름을 {@code X-Chzzk-Instance} 헤더로 알린다.
 * 요청이 여러 인스턴스로 나뉘는지 curl 등으로 확인할 때 쓴다. 단독 모드에서는 붙이지 않는다.
 */
@Component
@ConditionalOnProperty(name = "chzzk.cluster.enabled", havingValue = "true")
class InstanceHeaderFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Chzzk-Instance";

    private final String instanceId;

    InstanceHeaderFilter(@Value("${chzzk.instance-id:local}") String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, instanceId);
        chain.doFilter(request, response);
    }
}
