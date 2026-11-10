package kr.devcourse.chzzk.live;

/** 라이브 방송 시작 요청이 규칙을 어겼을 때. 메시지는 사용자에게 그대로 보여준다. */
public class InvalidLiveStartException extends RuntimeException {

    public InvalidLiveStartException(String message) {
        super(message);
    }
}
