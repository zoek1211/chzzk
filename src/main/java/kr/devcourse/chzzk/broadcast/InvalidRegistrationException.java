package kr.devcourse.chzzk.broadcast;

/** 등록 요청이 녹화방송 규칙을 어겼을 때. 메시지는 사용자에게 그대로 보여준다. */
public class InvalidRegistrationException extends RuntimeException {

    public InvalidRegistrationException(String message) {
        super(message);
    }
}
